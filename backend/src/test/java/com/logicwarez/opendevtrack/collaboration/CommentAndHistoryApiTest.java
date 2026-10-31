/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

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
 * Comments (module collaboration) and the changelog (module audit, written after the commit from the events of the
 * module issue). Users of {@code project/users.sql}: mario admin of ODT, luisa developer, paola viewer, ugo of another
 * tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class CommentAndHistoryApiTest {

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
                + "odt_collaboration.comment");
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
        for (int i = 0; i < 100 && count.getAsInt() < expected; i++) {
            Thread.sleep(50);
        }
        assertThat(count.getAsInt()).isEqualTo(expected);
    }

    private int rows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void theChangelogHasTheCreationAndEachFieldChanged() throws Exception {
        // created, type, summary, priority
        await(() -> rows("odt_audit.issue_change"), 4);
        mvc.perform(as(put("/api/issues/ODT-1"), luisa).content("""
                        {"type":"bug","summary":"Login fails with two tenants","priority":"high","version":0}"""))
                .andExpect(status().isOk());
        // Saving the same fields again changes nothing and writes nothing.
        mvc.perform(as(put("/api/issues/ODT-1"), luisa).content("""
                        {"type":"bug","summary":"Login fails with two tenants","priority":"high","version":1}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        await(() -> rows("odt_audit.issue_change"), 6);
        mvc.perform(as(get("/api/issues/ODT-1/history"), paola))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].field").value("summary"))
                .andExpect(jsonPath("$[0].from").value("Login fails"))
                .andExpect(jsonPath("$[0].to").value("Login fails with two tenants"))
                .andExpect(jsonPath("$[0].author").value("luisa"))
                .andExpect(jsonPath("$[1].field").value("priority"))
                .andExpect(jsonPath("$[1].from").value("medium"))
                .andExpect(jsonPath("$[2].field").value("created"))
                .andExpect(jsonPath("$[2].to").value("ODT-1"));
        mvc.perform(as(get("/api/issues/ODT-1/history"), ugo)).andExpect(status().isNotFound());
    }

    @Test
    void developersCommentAndAuthorsEditTheirOwn() throws Exception {
        String created = mvc.perform(as(post("/api/issues/ODT-1/comments"), luisa).content("{\"body\":\"Seen with **Chrome**\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author").value("luisa"))
                .andExpect(jsonPath("$._perm.edit").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        // A viewer reads but does not comment; an empty comment is refused.
        mvc.perform(as(post("/api/issues/ODT-1/comments"), paola).content("{\"body\":\"Me too\"}")).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/issues/ODT-1/comments"), mario).content("{\"body\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/issues/ODT-1/comments"), paola))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].body").value("Seen with **Chrome**"))
                .andExpect(jsonPath("$[0]._perm.edit").value(false))
                .andExpect(jsonPath("$[0]._perm.delete").value(false));
        // Only the author edits, from the version read.
        mvc.perform(as(put("/api/issues/ODT-1/comments/" + id), mario).content("{\"body\":\"Mine\",\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(put("/api/issues/ODT-1/comments/" + id), luisa).content("{\"body\":\"Seen with Chrome 140\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edited").value(true));
        mvc.perform(as(put("/api/issues/ODT-1/comments/" + id), luisa).content("{\"body\":\"Stale\",\"version\":0}"))
                .andExpect(status().isPreconditionFailed());
        // Another tenant does not see it; the admin of the project deletes it.
        mvc.perform(as(get("/api/issues/ODT-1/comments"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/issues/ODT-1/comments/" + id), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/issues/ODT-1/comments"), mario)).andExpect(jsonPath("$[0]._perm.delete").value(true));
        mvc.perform(as(delete("/api/issues/ODT-1/comments/" + id), mario)).andExpect(status().isNoContent());
        assertThat(rows("odt_collaboration.comment")).isZero();
    }

    @Test
    void theCommentsOfADeletedIssueGoWithIt() throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/comments"), luisa).content("{\"body\":\"One\"}")).andExpect(status().isCreated());
        mvc.perform(as(post("/api/issues/ODT-1/comments"), mario).content("{\"body\":\"Two\"}")).andExpect(status().isCreated());
        mvc.perform(as(delete("/api/issues/ODT-1"), mario)).andExpect(status().isNoContent());
        for (int i = 0; i < 100 && rows("odt_collaboration.comment") > 0; i++) {
            Thread.sleep(50);
        }
        assertThat(rows("odt_collaboration.comment")).isZero();
    }
}
