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
 * Watchers of the issues. Users of {@code project/users.sql}: mario admin of ODT, luisa developer, paola viewer, ugo
 * of another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class WatcherApiTest {
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
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Login fails\",\"assignee\":\"mario\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.watching").value(true));
    }

    private void watchers(String... usernames) throws Exception {
        mvc.perform(as(get("/api/issues/ODT-1/watchers"), luisa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username").value(org.hamcrest.Matchers.contains(usernames)));
    }

    @Test
    void theReporterAndTheAssigneeWatchFromTheStart() throws Exception {
        watchers("luisa", "mario");
        mvc.perform(as(get("/api/issues/ODT-1"), mario)).andExpect(jsonPath("$.watching").value(true));
        mvc.perform(as(get("/api/issues/ODT-1"), paola)).andExpect(jsonPath("$.watching").value(false));
        // A new assignee watches it too.
        mvc.perform(as(put("/api/issues/ODT-1"), luisa).content("{\"summary\":\"Login fails\",\"assignee\":\"paola\",\"version\":0}"))
                .andExpect(status().isOk());
        watchers("luisa", "mario", "paola");
        mvc.perform(as(get("/api/issues/ODT-1/watchers"), ugo)).andExpect(status().isNotFound());
    }

    @Test
    void whoeverSeesTheIssueWatchesItOrStops() throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/watchers"), paola)).andExpect(status().isNoContent());
        mvc.perform(as(post("/api/issues/ODT-1/watchers"), paola).content("{\"username\":\"me\"}"))
                .andExpect(status().isNoContent());
        watchers("luisa", "mario", "paola");
        mvc.perform(as(get("/api/issues/ODT-1/watchers"), paola))
                .andExpect(jsonPath("$[0]._perm.delete").value(false))
                .andExpect(jsonPath("$[2]._perm.delete").value(true));
        // A viewer does not change the others.
        mvc.perform(as(delete("/api/issues/ODT-1/watchers/luisa"), paola)).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/issues/ODT-1/watchers"), paola).content("{\"username\":\"luisa\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/issues/ODT-1/watchers/me"), paola)).andExpect(status().isNoContent());
        mvc.perform(as(delete("/api/issues/ODT-1/watchers/me"), paola)).andExpect(status().isNotFound());
        mvc.perform(as(post("/api/issues/ODT-1/watchers"), ugo)).andExpect(status().isNotFound());
    }

    @Test
    void aDeveloperAddsAndRemovesMembers() throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/watchers"), luisa).content("{\"username\":\"paola\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(post("/api/issues/ODT-1/watchers"), luisa).content("{\"username\":\"ugo\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(delete("/api/issues/ODT-1/watchers/mario"), luisa)).andExpect(status().isNoContent());
        watchers("luisa", "paola");
    }
}
