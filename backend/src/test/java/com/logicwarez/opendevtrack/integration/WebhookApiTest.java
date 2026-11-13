/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicwarez.authz.session.SessionAuthentication;
import com.logicwarez.authz.session.SessionContext;
import com.logicwarez.opendevtrack.TestInfrastructure;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The webhooks of ADR-21 (B) end to end, with a receiver on this machine ({@code odt.webhooks.allow-private}): the
 * API for an admin of the project, the payload and its Standard Webhooks signature, the retries and the give-up on a
 * clock, the webhook turned off after three days with a message to the admins, «Send a test», «Deliver again» and the
 * rotation of the secret. The job runs by hand ({@link Deliveries#deliver}), at the times the test gives. Users in
 * {@code project/users.sql}: mario creates the project, luisa is only a member, ugo is in another tenant.
 */
@SpringBootTest(properties = {"odt.webhooks.allow-private=true", "odt.webhooks.delay=PT1H"})
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class WebhookApiTest {

    private static final String ORIGIN = "http://localhost:8090";
    private static final List<String> MEMBER = List.of("odt.project.browse", "odt.user.browse");
    private static final List<String> CREATOR = List.of("odt.project.browse", "odt.user.browse", "odt.project.create");

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Deliveries job;

    MockMvc mvc;
    HttpServer receiver;
    final List<Received> received = new CopyOnWriteArrayList<>();
    volatile int answer = 204;
    final JsonMapper json = JsonMapper.builder().build();

    record Received(Map<String, String> headers, String body) {
    }

    private final SessionAuthentication mario = session("mario", "ALPHA", CREATOR);
    private final SessionAuthentication luisa = session("luisa", "ALPHA", MEMBER);
    private final SessionAuthentication ugo = session("ugo", "BETA", CREATOR);

    @BeforeEach
    void setUp() throws IOException {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_integration.webhook,"
                + " odt_integration.delivery, odt_notification.message CASCADE");
        receiver = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(Map.of(
                    "id", exchange.getRequestHeaders().getFirst("webhook-id"),
                    "timestamp", exchange.getRequestHeaders().getFirst("webhook-timestamp"),
                    "signature", exchange.getRequestHeaders().getFirst("webhook-signature")), body));
            exchange.sendResponseHeaders(answer, -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void tearDown() {
        receiver.stop(0);
    }

    private static SessionAuthentication session(String username, String tenant, List<String> permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(username, username, username + "@example.org", tenant,
                List.of(new SessionContext.Tenant(tenant, tenant)), List.of(), permissions, now, now.plus(10, ChronoUnit.HOURS)));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, SessionAuthentication who) {
        return request.with(authentication(who)).with(csrf()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON);
    }

    private String url() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    private JsonNode read(MockHttpServletRequestBuilder request, SessionAuthentication who, int expected) throws Exception {
        String body = mvc.perform(as(request, who)).andExpect(status().is(expected)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? null : json.readTree(body);
    }

    /** The project WEB with a webhook that wants {@code events}; its secret. */
    private JsonNode project(String events) throws Exception {
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"WEB\",\"name\":\"Web\"}"))
                .andExpect(status().isCreated());
        return read(post("/api/projects/WEB/webhooks").content(
                "{\"name\":\"Bot\",\"url\":\"" + url() + "\",\"events\":" + events + "}"), mario, 201);
    }

    private void issue(String summary) throws Exception {
        mvc.perform(as(post("/api/projects/WEB/issues"), mario).content("{\"type\":\"task\",\"summary\":\"" + summary + "\"}"))
                .andExpect(status().isCreated());
        TestInfrastructure.awaitListeners(jdbc);
    }

    @Test
    void anAdminManagesTheWebhooksOfTheProject() throws Exception {
        JsonNode created = project("[\"issue.created\",\"comment.posted\"]");
        String id = created.get("id").asString();
        assertThat(created.get("secret").asString()).startsWith("whsec_");
        String stored = jdbc.queryForObject("SELECT secret FROM odt_integration.webhook", String.class);
        assertThat(stored).as("kept encrypted (ADR-23)").startsWith("enc:1:")
                .doesNotContain(created.get("secret").asString().substring(6));
        assertThat(created.get("events").toString()).isEqualTo("[\"issue.created\",\"comment.posted\"]");

        JsonNode list = read(get("/api/projects/WEB/webhooks"), mario, 200);
        assertThat(list.get(0).get("name").asString()).isEqualTo("Bot");
        assertThat(list.get(0).has("secret")).as("the secret is shown once").isFalse();

        // Not an admin of the project, another tenant
        read(get("/api/projects/WEB/webhooks"), luisa, 403);
        mvc.perform(as(post("/api/projects/WEB/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        read(get("/api/projects/WEB/webhooks/" + id), luisa, 403);
        read(get("/api/projects/WEB/webhooks"), ugo, 404);

        // What is not valid
        JsonNode wrong = read(post("/api/projects/WEB/webhooks").content(
                "{\"name\":\" \",\"url\":\"ftp://example.org/x\",\"events\":[\"issue.renamed\"]}"), mario, 400);
        assertThat(wrong.get("errors").toString()).contains("/name", "/url", "/events");
        read(post("/api/projects/WEB/webhooks").content(
                "{\"name\":\"bot\",\"url\":\"" + url() + "\",\"events\":[\"*\"]}"), mario, 409);

        // A change from an older copy, then one from the current
        read(put("/api/projects/WEB/webhooks/" + id).content(
                "{\"name\":\"Bot\",\"url\":\"" + url() + "\",\"events\":[\"*\",\"issue.created\"],\"version\":7}"), mario, 412);
        JsonNode changed = read(put("/api/projects/WEB/webhooks/" + id).content("{\"name\":\"Chat bot\",\"url\":\"" + url()
                + "\",\"events\":[\"*\",\"issue.created\"],\"active\":false,\"version\":0}"), mario, 200);
        assertThat(changed.get("events").toString()).isEqualTo("[\"*\"]");
        assertThat(changed.get("active").asBoolean()).isFalse();

        read(delete("/api/projects/WEB/webhooks/" + id), mario, 204);
        read(get("/api/projects/WEB/webhooks/" + id), mario, 404);
    }

    @Test
    void anEventIsPostedSignedAndWithoutTheTextOfAComment() throws Exception {
        String secret = project("[\"issue.created\",\"comment.posted\"]").get("secret").asString();
        issue("First");
        mvc.perform(as(post("/api/issues/WEB-1/comments"), mario).content("{\"body\":\"A private remark\"}"))
                .andExpect(status().isCreated());
        TestInfrastructure.awaitListeners(jdbc);

        assertThat(job.deliver(Instant.now())).isEqualTo(2);
        assertThat(received).hasSize(2);
        Received first = received.stream().filter(r -> r.body().contains("issue.created")).findFirst().orElseThrow();
        JsonNode payload = json.readTree(first.body());
        assertThat(payload.get("id").asString()).isEqualTo(first.headers().get("id"));
        assertThat(payload.get("type").asString()).isEqualTo("issue.created");
        assertThat(payload.get("tenant").asString()).isEqualTo("ALPHA");
        assertThat(payload.get("project").asString()).isEqualTo("WEB");
        assertThat(payload.get("actor").asString()).isEqualTo("mario");
        assertThat(payload.get("data").get("key").asString()).isEqualTo("WEB-1");
        assertThat(payload.get("data").get("url").asString()).endsWith("/api/issues/WEB-1");
        assertThat(first.headers().get("signature")).isEqualTo("v1," + Signature.sign(secret, first.headers().get("id"),
                Long.parseLong(first.headers().get("timestamp")), first.body()));
        Received comment = received.stream().filter(r -> r.body().contains("comment.posted")).findFirst().orElseThrow();
        assertThat(comment.body()).doesNotContain("A private remark");

        // Delivered: nothing more to do, and the list shows the last delivery
        assertThat(job.deliver(Instant.now().plusSeconds(3600))).isZero();
        JsonNode list = read(get("/api/projects/WEB/webhooks"), mario, 200);
        assertThat(list.get(0).get("lastStatus").asString()).isEqualTo("delivered");
        assertThat(list.get(0).get("lastCode").asInt()).isEqualTo(204);
    }

    @Test
    void failuresAreRetriedThenGivenUpAndThreeDaysTurnTheWebhookOff() throws Exception {
        String id = project("[\"*\"]").get("id").asString();
        answer = 500;
        issue("First");
        Instant t = Instant.now();
        assertThat(job.deliver(t)).isEqualTo(1);
        // Not due again before a minute
        assertThat(job.deliver(t.plusSeconds(30))).isZero();
        // Six retries, the last one three days later: given up, and the webhook turned off
        for (Duration after : List.of(Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofHours(1),
                Duration.ofHours(4), Duration.ofDays(1), Duration.ofDays(3).plusHours(1))) {
            assertThat(job.deliver(t.plus(after))).as("attempt after %s", after).isEqualTo(1);
        }
        assertThat(received).hasSize(7);
        assertThat(received.stream().map(r -> r.headers().get("id")).distinct()).as("one webhook-id").hasSize(1);
        JsonNode deliveries = read(get("/api/projects/WEB/webhooks/" + id + "/deliveries"), mario, 200);
        assertThat(deliveries.get(0).get("status").asString()).isEqualTo("failed");
        assertThat(deliveries.get(0).get("attempts").asInt()).isEqualTo(7);
        assertThat(deliveries.get(0).get("lastCode").asInt()).isEqualTo(500);
        assertThat(read(get("/api/projects/WEB/webhooks/" + id), mario, 200).get("active").asBoolean()).isFalse();

        // The admins are told
        TestInfrastructure.awaitListeners(jdbc);
        JsonNode messages = read(get("/api/notifications"), mario, 200);
        assertThat(messages.get(0).get("title").asString()).isEqualTo("Webhook Bot of WEB turned off");
        assertThat(messages.get(0).get("screen").asString()).isEqualTo("project-detail");

        // Off: new events are not queued
        answer = 204;
        issue("Second");
        assertThat(job.deliver(t.plus(Duration.ofDays(4)))).isZero();
    }

    @Test
    void aTestAndADeliveryAgainAndARotatedSecret() throws Exception {
        JsonNode created = project("[\"sprint.started\"]");
        String id = created.get("id").asString();
        String before = created.get("secret").asString();

        JsonNode ping = read(post("/api/projects/WEB/webhooks/" + id + "/ping"), mario, 201);
        assertThat(ping.get("event").asString()).isEqualTo("ping");
        // An issue is not among the events it wants
        issue("First");
        assertThat(job.deliver(Instant.now())).isEqualTo(1);
        assertThat(json.readTree(received.getFirst().body()).get("data").get("webhook").asString()).isEqualTo("Bot");

        // Delivered again: the same id and body
        read(post("/api/projects/WEB/webhooks/" + id + "/deliveries/" + ping.get("id").asString() + "/again"), mario, 200);
        assertThat(job.deliver(Instant.now())).isEqualTo(1);
        assertThat(received.get(1).headers().get("id")).isEqualTo(received.getFirst().headers().get("id"));
        assertThat(received.get(1).body()).isEqualTo(received.getFirst().body());

        // A new secret: both sign for a day
        String after = read(post("/api/projects/WEB/webhooks/" + id + "/secret"), mario, 200).get("secret").asString();
        assertThat(after).isNotEqualTo(before);
        read(post("/api/projects/WEB/webhooks/" + id + "/ping"), mario, 201);
        assertThat(job.deliver(Instant.now())).isEqualTo(1);
        Received rotated = received.get(2);
        long timestamp = Long.parseLong(rotated.headers().get("timestamp"));
        assertThat(rotated.headers().get("signature")).isEqualTo(
                "v1," + Signature.sign(after, rotated.headers().get("id"), timestamp, rotated.body())
                        + " v1," + Signature.sign(before, rotated.headers().get("id"), timestamp, rotated.body()));
    }
}
