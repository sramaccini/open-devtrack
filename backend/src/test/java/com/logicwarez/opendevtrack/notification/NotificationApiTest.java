/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.logicwarez.authz.session.SessionAuthentication;
import com.logicwarez.authz.session.SessionContext;
import com.logicwarez.opendevtrack.TestInfrastructure;
import jakarta.servlet.Filter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.IntSupplier;
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

/**
 * Mentions in the comments (module collaboration) and the messages for the user (module notification, written after the
 * commit from the events), ADR-13. Users of {@code project/users.sql}: mario admin of ODT, luisa developer and
 * reporter of ODT-1, paola viewer, ugo of another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class NotificationApiTest {

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

    MockMvc mvc;

    private static SessionAuthentication session(String username, String tenant, List<String> permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(username, username, username + "@example.org", tenant,
                List.of(new SessionContext.Tenant(tenant, tenant)), List.of(), permissions, now, now.plus(10, ChronoUnit.HOURS)));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, SessionAuthentication who) {
        return request.with(authentication(who)).with(csrf()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON);
    }

    private final SessionAuthentication mario = session("mario", "ALPHA", CREATOR);
    private final SessionAuthentication luisa = session("luisa", "ALPHA", MEMBER);
    private final SessionAuthentication paola = session("paola", "ALPHA", MEMBER);
    private final SessionAuthentication ugo = session("ugo", "BETA", CREATOR);

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_audit.issue_change, "
                + "odt_collaboration.comment, odt_notification.message CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Login fails\",\"type\":\"bug\"}"))
                .andExpect(status().isCreated());
    }

    /** The listeners run after the commit, on another thread: wait until the expected rows are there. */
    private static void await(IntSupplier count, int expected) throws InterruptedException {
        for (int i = 0; i < 100 && count.getAsInt() != expected; i++) {
            Thread.sleep(50);
        }
        assertThat(count.getAsInt()).isEqualTo(expected);
    }

    private int messages() {
        return jdbc.queryForObject("SELECT count(*) FROM odt_notification.message", Integer.class);
    }

    private List<String> mentions() {
        return jdbc.queryForList("SELECT username FROM odt_collaboration.mention ORDER BY username", String.class);
    }

    private List<String> watchers() {
        return jdbc.queryForList("SELECT username FROM odt_issue.watcher ORDER BY username", String.class);
    }

    @Test
    void mentionsOfMembersAreFoundInTheTextAndTold() throws Exception {
        String created = mvc.perform(as(post("/api/issues/ODT-1/comments"), luisa).content("""
                        {"body":"Hi @Mario and @paola, see @nobody; write to me@example.org or @luisa."}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // Members only, as written in the project; the author is kept as a mention but not told.
        assertThat(mentions()).containsExactly("luisa", "mario", "paola");
        assertThat(watchers()).containsExactly("luisa", "mario", "paola");
        await(this::messages, 2);
        mvc.perform(as(get("/api/notifications"), mario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("luisa mentioned you in ODT-1"))
                .andExpect(jsonPath("$[0].text").value("Hi @Mario and @paola, see @nobody; write to me@example.org or @luisa."))
                .andExpect(jsonPath("$[0].read").value(false))
                .andExpect(jsonPath("$[0].screen").value("issue-detail"))
                .andExpect(jsonPath("$[0].params.key").value("ODT"))
                .andExpect(jsonPath("$[0].params.issue").value("ODT-1"));
        mvc.perform(as(get("/api/notifications"), paola).header("Accept-Language", "it-IT,it;q=0.9"))
                .andExpect(jsonPath("$[0].title").value("luisa ti ha menzionato in ODT-1"));
        mvc.perform(as(get("/api/notifications"), luisa)).andExpect(jsonPath("$.length()").value(0));

        // An edit tells only the people newly mentioned: nobody here, since mario and paola were already.
        String id = JsonPath.read(created, "$.id");
        mvc.perform(as(put("/api/issues/ODT-1/comments/" + id), luisa).content("""
                        {"body":"Hi @mario and @paola, fixed.","version":0}"""))
                .andExpect(status().isOk());
        assertThat(mentions()).containsExactly("mario", "paola");
        Thread.sleep(300);
        assertThat(messages()).isEqualTo(2);

        // A new comment is told to the watchers, never to its author.
        mvc.perform(as(post("/api/issues/ODT-1/comments"), mario).content("{\"body\":\"Thanks\"}"))
                .andExpect(status().isCreated());
        await(this::messages, 4);
        mvc.perform(as(get("/api/notifications"), luisa))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("mario commented on ODT-1"))
                .andExpect(jsonPath("$[0].text").value("Thanks"));
        mvc.perform(as(get("/api/notifications"), paola))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("mario commented on ODT-1"));
    }

    @Test
    void assignmentsAndStatusChangesOfWatchedIssues() throws Exception {
        mvc.perform(as(put("/api/issues/ODT-1"), mario).content("""
                        {"type":"bug","summary":"Login fails","priority":"medium","assignee":"paola","version":0}"""))
                .andExpect(status().isOk());
        await(this::messages, 1);
        mvc.perform(as(get("/api/notifications"), paola))
                .andExpect(jsonPath("$[0].title").value("mario assigned ODT-1 to you"));

        // luisa (reporter) and paola (assignee) watch the issue; mario moved it and is not told.
        mvc.perform(as(post("/api/issues/ODT-1/transitions"), mario).content("{\"transition\":\"start\",\"version\":1}"))
                .andExpect(status().isOk());
        await(this::messages, 3);
        mvc.perform(as(get("/api/notifications"), luisa).header("Accept-Language", "it"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("mario ha spostato ODT-1 in In corso"))
                .andExpect(jsonPath("$[0].text").value("Da Da fare a In corso"));
        mvc.perform(as(get("/api/notifications"), mario)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void readOnlyOwnMessagesInTheTenantAndGoneWithTheIssue() throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/comments"), luisa).content("{\"body\":\"@mario look\"}"))
                .andExpect(status().isCreated());
        await(this::messages, 1);
        String list = mvc.perform(as(get("/api/notifications"), mario)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(list, "$[0].id");

        // Someone else's identifier marks nothing.
        mvc.perform(as(post("/api/notifications/read"), luisa).content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/notifications"), mario)).andExpect(jsonPath("$[0].read").value(false));
        mvc.perform(as(post("/api/notifications/read"), mario).content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/notifications"), mario)).andExpect(jsonPath("$[0].read").value(true));
        mvc.perform(as(post("/api/notifications/read"), mario).content("{\"ids\":[]}")).andExpect(status().isBadRequest());

        // Another tenant sees none of them.
        mvc.perform(as(get("/api/notifications"), ugo)).andExpect(jsonPath("$.length()").value(0));

        // A deleted issue takes its messages away.
        mvc.perform(as(delete("/api/issues/ODT-1"), mario)).andExpect(status().isNoContent());
        await(this::messages, 0);
    }
}
