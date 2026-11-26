/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
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
 * The flag and the votes of an issue. Users of {@code project/users.sql}: mario admin of ODT, luisa developer, paola
 * viewer, ugo of another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class FlagAndVoteApiTest {
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
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Login fails\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.flagged").value(false))
                .andExpect(jsonPath("$.votes").value(0))
                .andExpect(jsonPath("$.voted").value(false));
    }

    @Test
    void aDeveloperFlagsTheIssueAndTheHistorySaysSo() throws Exception {
        mvc.perform(as(put("/api/issues/ODT-1/flag"), paola)).andExpect(status().isForbidden());
        mvc.perform(as(put("/api/issues/ODT-1/flag"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(put("/api/issues/ODT-1/flag"), luisa)).andExpect(status().isNoContent());
        // Flagging again changes nothing
        mvc.perform(as(put("/api/issues/ODT-1/flag"), luisa)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/issues/ODT-1"), paola))
                .andExpect(jsonPath("$.flagged").value(true))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(as(delete("/api/issues/ODT-1/flag"), paola)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/issues/ODT-1/flag"), mario)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/issues/ODT-1"), paola)).andExpect(jsonPath("$.flagged").value(false));

        TestInfrastructure.awaitListeners(jdbc);
        mvc.perform(as(get("/api/issues/ODT-1/history"), paola))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.field == 'flagged')].to").value(hasItem("Impediment")))
                .andExpect(jsonPath("$[?(@.field == 'flagged')].from").value(hasItem("Impediment")));
    }

    @Test
    void whoeverSeesTheIssueVotesOnceOrTakesTheVoteBack() throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/votes"), paola)).andExpect(status().isNoContent());
        mvc.perform(as(post("/api/issues/ODT-1/votes"), paola)).andExpect(status().isNoContent());
        mvc.perform(as(post("/api/issues/ODT-1/votes"), mario)).andExpect(status().isNoContent());
        mvc.perform(as(post("/api/issues/ODT-1/votes"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/issues/ODT-1"), paola))
                .andExpect(jsonPath("$.votes").value(2))
                .andExpect(jsonPath("$.voted").value(true))
                // A vote is not a change of the issue
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(as(get("/api/issues/ODT-1"), luisa)).andExpect(jsonPath("$.voted").value(false));
        mvc.perform(as(get("/api/issues/ODT-1/votes"), luisa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username").value(contains("mario", "paola")));
        mvc.perform(as(get("/api/issues/ODT-1/votes"), ugo)).andExpect(status().isNotFound());

        mvc.perform(as(delete("/api/issues/ODT-1/votes/me"), paola)).andExpect(status().isNoContent());
        mvc.perform(as(delete("/api/issues/ODT-1/votes/me"), paola)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/issues/ODT-1"), paola))
                .andExpect(jsonPath("$.votes").value(1))
                .andExpect(jsonPath("$.voted").value(false));
    }
}
