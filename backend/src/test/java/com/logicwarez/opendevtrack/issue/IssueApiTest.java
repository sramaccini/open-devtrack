/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

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
import jakarta.servlet.Filter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Issues over HTTP (users in {@code project/users.sql}): mario creates the project ODT and is its admin, luisa is a
 * developer, paola a viewer; ugo is in another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class IssueApiTest {

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
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
    }

    private void createFirst() throws Exception {
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("""
                        {"type":"bug","summary":" Login fails ","description":"With **two** tenants",
                         "priority":"high","assignee":"luisa","labels":["Auth","auth","ui"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("ODT-1"))
                .andExpect(jsonPath("$.summary").value("Login fails"))
                .andExpect(jsonPath("$.status").value("todo"))
                .andExpect(jsonPath("$.reporter").value("luisa"))
                .andExpect(jsonPath("$.labels.length()").value(2))
                .andExpect(jsonPath("$.labels[0]").value("auth"))
                .andExpect(jsonPath("$._perm.edit").value(true))
                .andExpect(jsonPath("$._perm.delete").value(false));
    }

    @Test
    void aDeveloperCreatesAndTheIssuesAreNumbered() throws Exception {
        createFirst();
        mvc.perform(as(post("/api/projects/ODT/issues"), mario).content("{\"summary\":\"Board\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("ODT-2"))
                .andExpect(jsonPath("$.type").value("task"))
                .andExpect(jsonPath("$.priority").value("medium"));
        mvc.perform(as(get("/api/projects/ODT/issues"), paola))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].key").value("ODT-2"));
        mvc.perform(as(get("/api/projects/ODT/issues").param("assignee", "me"), luisa))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value("ODT-1"));
        mvc.perform(as(get("/api/projects/ODT/issues").param("q", "LOGIN").param("status", "todo"), luisa))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(as(get("/api/issues/ODT-1"), paola))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("With **two** tenants"))
                .andExpect(jsonPath("$._perm.edit").value(false));
    }

    @Test
    void theRoleInTheProjectDecides() throws Exception {
        createFirst();
        // A viewer neither creates nor edits; a developer does not delete.
        mvc.perform(as(post("/api/projects/ODT/issues"), paola).content("{\"summary\":\"Mine\"}")).andExpect(status().isForbidden());
        mvc.perform(as(put("/api/issues/ODT-1"), paola).content("{\"summary\":\"Mine\",\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/issues/ODT-1"), luisa)).andExpect(status().isForbidden());
        // A user of the tenant who is not a member sees the project but not its issues.
        mvc.perform(as(delete("/api/projects/ODT/members/paola"), mario)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/projects/ODT/issues"), paola)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/issues/ODT-1"), paola)).andExpect(status().isForbidden());
        // The admin deletes.
        mvc.perform(as(delete("/api/issues/ODT-1"), mario)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/issues/ODT-1"), mario)).andExpect(status().isNotFound());
    }

    @Test
    void anotherTenantDoesNotSeeTheIssues() throws Exception {
        createFirst();
        mvc.perform(as(get("/api/issues/ODT-1"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/projects/ODT/issues"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(post("/api/projects/ODT/issues"), ugo).content("{\"summary\":\"Here\"}")).andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/issues/ODT-1"), ugo)).andExpect(status().isNotFound());
    }

    @Test
    void fieldsAreChecked() throws Exception {
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"A\",\"type\":\"chore\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"A\",\"priority\":\"urgent\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"A\",\"assignee\":\"ugo\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"A\",\"labels\":[\"two words\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/issues/odt-1"), luisa)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/issues/ODT-0"), luisa)).andExpect(status().isNotFound());
    }

    @Test
    void anOldVersionIsRefused() throws Exception {
        createFirst();
        mvc.perform(as(put("/api/issues/ODT-1"), luisa).content("""
                        {"type":"bug","summary":"Login fails with two tenants","priority":"highest","assignee":"mario",
                         "labels":["auth"],"version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee").value("mario"))
                .andExpect(jsonPath("$.priority").value("highest"))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(as(put("/api/issues/ODT-1"), mario).content("{\"summary\":\"Stale\",\"version\":0}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(put("/api/issues/ODT-1"), mario).content("{\"summary\":\"No version\"}"))
                .andExpect(status().isPreconditionFailed());
    }
}
