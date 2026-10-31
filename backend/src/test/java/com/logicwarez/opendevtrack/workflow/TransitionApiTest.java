/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
 * The workflow of a project and the transitions of its issues (ADR-10). Users of {@code project/users.sql}: mario admin
 * of ODT, luisa developer, paola viewer, ugo of another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class TransitionApiTest {

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
                + "odt_workflow.workflow CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Login fails\",\"type\":\"bug\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("todo"));
    }

    private void move(SessionAuthentication who, String transition, long version, int expected) throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/transitions"), who)
                        .content("{\"transition\":\"" + transition + "\",\"version\":" + version + "}"))
                .andExpect(status().is(expected));
    }

    @Test
    void aNewProjectHasTheDefaultWorkflow() throws Exception {
        mvc.perform(as(get("/api/projects/ODT/workflow/statuses"), paola))
                .andExpect(jsonPath("$[*].code").value(org.hamcrest.Matchers.contains("todo", "in_progress", "done")))
                .andExpect(jsonPath("$[2].category").value("done"));
        mvc.perform(as(get("/api/projects/ODT/workflow/transitions"), paola))
                .andExpect(jsonPath("$[*].code").value(org.hamcrest.Matchers.contains("start", "finish", "reopen")))
                .andExpect(jsonPath("$[0]._perm.edit").value(false));
        mvc.perform(as(get("/api/projects/ODT/workflow/statuses"), ugo)).andExpect(status().isNotFound());
    }

    @Test
    void anIssueGoesThroughTheWorkflow() throws Exception {
        mvc.perform(as(get("/api/issues/ODT-1/transitions"), luisa))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].code").value("start"))
                .andExpect(jsonPath("$[0].to").value("in_progress"));
        // A viewer sees no transition and cannot run one.
        mvc.perform(as(get("/api/issues/ODT-1/transitions"), paola)).andExpect(jsonPath("$.length()").value(0));
        move(paola, "start", 0, 403);
        // Not from this status; unknown.
        move(luisa, "finish", 0, 409);
        move(luisa, "fly", 0, 400);
        move(luisa, "start", 0, 200);
        move(luisa, "finish", 0, 412);
        move(luisa, "finish", 1, 200);
        mvc.perform(as(get("/api/issues/ODT-1"), luisa)).andExpect(jsonPath("$.status").value("done"));
        mvc.perform(as(get("/api/projects/ODT/issues").param("status", "done"), luisa)).andExpect(jsonPath("$.length()").value(1));
        // Another tenant does not see it.
        move(ugo, "reopen", 2, 404);
        mvc.perform(as(get("/api/issues/ODT-1/transitions"), ugo)).andExpect(status().isNotFound());
        // The changelog has the two moves.
        for (int i = 0; i < 100 && statusRows() < 2; i++) {
            Thread.sleep(50);
        }
        assertThat(statusRows()).isEqualTo(2);
    }

    private int statusRows() {
        return jdbc.queryForObject("SELECT count(*) FROM odt_audit.issue_change WHERE field = 'status'", Integer.class);
    }

    @Test
    void aGuardDecides() throws Exception {
        // Only an admin of the project changes the workflow; a guard that is not CEL is refused.
        mvc.perform(as(put("/api/projects/ODT/workflow/transitions/start"), luisa).content("{\"guard\":\"true\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(put("/api/projects/ODT/workflow/transitions/start"), mario).content("{\"guard\":\"issue.assignee !=\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(put("/api/projects/ODT/workflow/transitions/start"), mario)
                        .content("{\"guard\":\"issue.assignee != null && issue.status == 'in_progress'\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guard").value("issue.assignee != null && issue.status == 'in_progress'"));
        // Without an assignee the transition is not offered and does not run.
        mvc.perform(as(get("/api/issues/ODT-1/transitions"), luisa)).andExpect(jsonPath("$.length()").value(0));
        move(luisa, "start", 0, 409);
        mvc.perform(as(put("/api/issues/ODT-1"), luisa).content("{\"type\":\"bug\",\"summary\":\"Login fails\",\"assignee\":\"luisa\",\"version\":0}"))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/issues/ODT-1/transitions"), luisa)).andExpect(jsonPath("$[0].code").value("start"));
        move(luisa, "start", 1, 200);
        // can() sees the project permissions of the user: only an admin reopens.
        mvc.perform(as(put("/api/projects/ODT/workflow/transitions/finish"), mario).content("{\"guard\":\"can('odt.issue.delete')\"}"))
                .andExpect(status().isOk());
        move(luisa, "finish", 2, 409);
        move(mario, "finish", 2, 200);
        // An empty guard removes it.
        mvc.perform(as(put("/api/projects/ODT/workflow/transitions/finish"), mario).content("{\"guard\":\"\"}"))
                .andExpect(jsonPath("$.guard").doesNotExist());
    }

    @Test
    void theAdminOfTheTenantMovesIssuesOfEveryProject() throws Exception {
        SessionAuthentication admin = session("paola", "ALPHA", List.of("odt.project.browse", "odt.project.admin"));
        move(admin, "start", 0, 200);
    }
}
