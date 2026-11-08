/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
 * Editing the workflow of a project (ADR-20, A): statuses and transitions added, changed and removed by an admin of the
 * project, with the rules of A.2, the move of the issues of a removed status (A.3) and the columns of a board (A.5).
 * Users of {@code project/users.sql}: mario admin of ODT, luisa developer.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class WorkflowEditApiTest {

    private static final String ORIGIN = "http://localhost:8090";
    private static final String STATUSES = "/api/projects/ODT/workflow/statuses";
    private static final String TRANSITIONS = "/api/projects/ODT/workflow/transitions";
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

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_audit.issue_change, "
                + "odt_workflow.workflow, odt_agile.board, odt_agile.board_column CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Login fails\",\"type\":\"bug\"}"))
                .andExpect(status().isCreated());
    }

    private void call(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        mvc.perform(as(request, mario).content(body)).andExpect(status().is(expected));
    }

    /** The version of the workflow, as the rows of its statuses carry it. */
    private int version() throws Exception {
        String body = mvc.perform(as(get(STATUSES), luisa)).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[0].version");
    }

    private void move(String transition) throws Exception {
        String body = mvc.perform(as(get("/api/issues/ODT-1"), luisa)).andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(body, "$.version");
        mvc.perform(as(post("/api/issues/ODT-1/transitions"), luisa)
                        .content("{\"transition\":\"" + transition + "\",\"version\":" + version + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void statusesAreAddedAndChangedByTheAdmin() throws Exception {
        mvc.perform(as(post(STATUSES), luisa).content("{\"code\":\"in_review\",\"category\":\"in_progress\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post(STATUSES), mario).content("{\"code\":\"in_review\",\"category\":\"in_progress\",\"position\":3}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(3))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(as(get(STATUSES), luisa))
                .andExpect(jsonPath("$[*].code").value(contains("todo", "in_progress", "in_review", "done")))
                .andExpect(jsonPath("$[0].issues").value(1))
                .andExpect(jsonPath("$[2].issues").value(0))
                .andExpect(jsonPath("$[2]._perm.edit").value(false));
        // The lookups of the screens look for a part of the code.
        mvc.perform(as(get(STATUSES).param("q", "Progr"), luisa)).andExpect(jsonPath("$[*].code").value(contains("in_progress")));
        // The same code again; a code, a category or a position that cannot be; a first status that is not to do.
        call(post(STATUSES), "{\"code\":\"in_review\",\"category\":\"done\"}", 409);
        call(post(STATUSES), "{\"code\":\"In review\",\"category\":\"done\"}", 400);
        call(post(STATUSES), "{\"code\":\"waiting\",\"category\":\"blocked\"}", 400);
        call(post(STATUSES), "{\"code\":\"waiting\",\"category\":\"todo\",\"position\":9}", 400);
        call(post(STATUSES), "{\"code\":\"rejected\",\"category\":\"done\",\"position\":1}", 400);
        // A change made on an older copy; a category left without statuses; a first status out of to do.
        call(put(STATUSES + "/in_review"), "{\"category\":\"done\",\"version\":0}", 412);
        call(put(STATUSES + "/in_review"), "{\"category\":\"done\"}", 412);
        call(put(STATUSES + "/done"), "{\"category\":\"in_progress\",\"version\":1}", 400);
        call(put(STATUSES + "/todo"), "{\"position\":2,\"version\":1}", 400);
        call(put(STATUSES + "/nothing"), "{\"position\":2,\"version\":1}", 404);
        mvc.perform(as(put(STATUSES + "/in_review"), mario).content("{\"position\":4,\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(4))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(as(get(STATUSES), luisa))
                .andExpect(jsonPath("$[*].code").value(contains("todo", "in_progress", "done", "in_review")))
                .andExpect(jsonPath("$[*].position").value(contains(1, 2, 3, 4)));
        // The refused changes left nothing behind.
        mvc.perform(as(get(STATUSES), luisa)).andExpect(jsonPath("$[0].category").value("todo"));
    }

    @Test
    void aRemovedStatusMovesItsIssues() throws Exception {
        call(post(STATUSES), "{\"code\":\"in_review\",\"category\":\"in_progress\",\"position\":3}", 201);
        call(post(TRANSITIONS), "{\"code\":\"review\",\"from\":\"in_progress\",\"to\":\"in_review\"}", 201);
        call(post(TRANSITIONS), "{\"code\":\"approve\",\"from\":\"in_review\",\"to\":\"done\"}", 201);
        move("start");
        move("review");
        int version = version();
        call(post(STATUSES + "/in_review/remove"), "{\"moveTo\":\"nowhere\",\"version\":" + version + "}", 400);
        call(post(STATUSES + "/in_review/remove"), "{\"moveTo\":\"in_review\",\"version\":" + version + "}", 400);
        call(post(STATUSES + "/in_review/remove"), "{\"moveTo\":\"in_progress\"}", 412);
        call(post(STATUSES + "/todo/remove"), "{\"moveTo\":\"in_progress\",\"version\":" + version + "}", 400);
        mvc.perform(as(post(STATUSES + "/in_review/remove"), mario).content("{\"moveTo\":\"in_progress\",\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved").value(1));
        mvc.perform(as(get("/api/issues/ODT-1"), luisa)).andExpect(jsonPath("$.status").value("in_progress"));
        mvc.perform(as(get(STATUSES), luisa))
                .andExpect(jsonPath("$[*].code").value(contains("todo", "in_progress", "done")))
                .andExpect(jsonPath("$[*].position").value(contains(1, 2, 3)));
        mvc.perform(as(get(TRANSITIONS), luisa))
                .andExpect(jsonPath("$[*].code").value(contains("start", "finish", "reopen")));
        // The move is in the history of the issue, by the admin who removed the status.
        TestInfrastructure.awaitListeners(jdbc);
        assertThat(jdbc.queryForList("SELECT author || ' ' || old_value || ' ' || new_value FROM odt_audit.issue_change "
                + "WHERE field = 'status' ORDER BY changed_at", String.class))
                .last().isEqualTo("mario in_review in_progress");
    }

    @Test
    void transitionsAreAddedChangedAndRemoved() throws Exception {
        mvc.perform(as(post(TRANSITIONS), mario).content("{\"code\":\"cancel\",\"to\":\"done\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.from").doesNotExist());
        call(post(TRANSITIONS), "{\"code\":\"cancel\",\"to\":\"done\"}", 409);
        call(post(TRANSITIONS), "{\"code\":\"fly\",\"to\":\"nowhere\"}", 400);
        call(post(TRANSITIONS), "{\"code\":\"stay\",\"from\":\"todo\",\"to\":\"todo\"}", 400);
        call(post(TRANSITIONS), "{\"code\":\"wrong\",\"to\":\"done\",\"guard\":\"issue.assignee !=\"}", 400);
        mvc.perform(as(post(TRANSITIONS), luisa).content("{\"code\":\"skip\",\"to\":\"done\"}")).andExpect(status().isForbidden());
        // From every status: offered from to do.
        mvc.perform(as(get("/api/issues/ODT-1/transitions"), luisa))
                .andExpect(jsonPath("$[*].code").value(contains("start", "cancel")));
        int version = version();
        mvc.perform(as(put(TRANSITIONS + "/cancel"), mario)
                        .content("{\"from\":\"in_progress\",\"to\":\"done\",\"guard\":\"issue.assignee != null\",\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("in_progress"));
        mvc.perform(as(get("/api/issues/ODT-1/transitions"), luisa)).andExpect(jsonPath("$[*].code").value(contains("start")));
        mvc.perform(as(delete(TRANSITIONS + "/cancel").param("version", String.valueOf(version)), mario))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(delete(TRANSITIONS + "/cancel").param("version", String.valueOf(version + 1)), mario))
                .andExpect(status().isNoContent());
        mvc.perform(as(get(TRANSITIONS), luisa)).andExpect(jsonPath("$[*].code").value(contains("start", "finish", "reopen")));
    }

    @Test
    void aNewStatusIsShownInTheColumnOfItsCategory() throws Exception {
        String body = mvc.perform(as(post("/api/projects/ODT/boards"), mario).content("""
                        {"name":"Team","kind":"kanban","lane":"none","columns":"Open: todo\\nDoing: in_progress\\nDone: done"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String board = JsonPath.read(body, "$.id");
        call(post(STATUSES), "{\"code\":\"in_review\",\"category\":\"in_progress\",\"position\":3}", 201);
        call(post(TRANSITIONS), "{\"code\":\"review\",\"from\":\"in_progress\",\"to\":\"in_review\"}", 201);
        move("start");
        move("review");
        // Shown in Doing, where in_progress is; no column is added.
        mvc.perform(as(get("/api/boards/" + board + "/columns"), luisa))
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(as(get("/api/boards/" + board + "/cards"), luisa))
                .andExpect(jsonPath("$[0].key").value("ODT-1"))
                .andExpect(jsonPath("$[0].column").value("1"));
    }
}
