/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicwarez.authz.session.SessionAuthentication;
import com.logicwarez.authz.session.SessionContext;
import com.logicwarez.opendevtrack.TestInfrastructure;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The telemetry of the Quadrava runtime ({@code contracts/telemetry.yaml}, #57): the checks of its conformance kit, and
 * the payload redacted on its way to the collector, here a small HTTP server.
 */
@SpringBootTest(properties = "odt.telemetry.max-bytes=4096")
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
class TelemetryApiTest {

    private static final String ORIGIN = "http://localhost:8090";
    private static final String TRACES = """
            {"resourceSpans":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"odt-test"}}]},
            "scopeSpans":[{"scope":{"name":"test"},"spans":[{"traceId":"4bf92f3577b34da6a3ce929d0e0e4736",
            "spanId":"00f067aa0ba902b7","name":"GET /api/issues?reporter=mario.rossi@example.org","kind":3}]}]}]}""";
    private static final String LOGS = """
            {"resourceLogs":[{"resource":{"attributes":[]},"scopeLogs":[{"scope":{"name":"test"},
            "logRecords":[{"severityNumber":17,"body":{"stringValue":"Failed for IT60X0542811101000000123456"}}]}]}]}""";

    private static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
    private static final HttpServer COLLECTOR = collector();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("odt.telemetry.otlp-endpoint", () -> "http://localhost:" + COLLECTOR.getAddress().getPort() + "/");
    }

    @AfterAll
    static void stop() {
        COLLECTOR.stop(0);
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    MockMvc mvc;

    private static HttpServer collector() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                RECEIVED.add(exchange.getRequestURI().getPath() + " " + body);
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SessionAuthentication session() {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext("anna", "anna", "anna@example.org", "acme",
                List.of(new SessionContext.Tenant("acme", "acme")), List.of(), List.of(), now, now.plus(10, ChronoUnit.HOURS)));
    }

    private static MockHttpServletRequestBuilder send(String signal, String body) {
        return post("/telemetry/v1/" + signal).with(authentication(session())).with(csrf()).header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        RECEIVED.clear();
    }

    @Test
    void tracesAndLogsAreAcceptedAndForwardedRedacted() throws Exception {
        mvc.perform(send("traces", TRACES)).andExpect(status().isAccepted());
        String traces = RECEIVED.poll(10, TimeUnit.SECONDS);
        assertThat(traces).startsWith("/v1/traces ").contains("reporter=" + TelemetryController.MASK)
                .doesNotContain("mario.rossi");

        mvc.perform(send("logs", LOGS)).andExpect(status().isAccepted());
        String logs = RECEIVED.poll(10, TimeUnit.SECONDS);
        assertThat(logs).startsWith("/v1/logs ").contains("Failed for " + TelemetryController.MASK)
                .doesNotContain("IT60X");
    }

    @Test
    void aBodyThatIsNotThePayloadOfItsSignalIsRefused() throws Exception {
        mvc.perform(send("logs", "{not json")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").exists());
        mvc.perform(send("traces", LOGS)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("The resourceSpans list is missing (OTLP/HTTP JSON payload of traces)"));
        mvc.perform(send("logs", "[1, 2, 3]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("The body is not a JSON object"));
        assertThat(RECEIVED).isEmpty();
    }

    @Test
    void aBodyOverTheLimitIsRefused() throws Exception {
        String big = "{\"resourceLogs\":[{\"body\":\"" + "x".repeat(5000) + "\"}]}";
        mvc.perform(send("logs", big)).andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.title").value("Body over the limit of 4096 bytes"));
    }

    @Test
    void withoutASessionTheAnswerIs401() throws Exception {
        mvc.perform(post("/telemetry/v1/traces").with(csrf()).header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(TRACES)).andExpect(status().isUnauthorized());
    }

    @Test
    void personalDataIsRedacted() {
        assertThat(TelemetryController.redact(
                "mail anna.bianchi@example.org cf RSSMRA85T10A562S iban IT60X0542811101000000123456 jwt eyJhbGciOi.eyJzdWIi.c2ln ok"))
                .isEqualTo("mail [REDACTED] cf [REDACTED] iban [REDACTED] jwt [REDACTED] ok");
    }
}
