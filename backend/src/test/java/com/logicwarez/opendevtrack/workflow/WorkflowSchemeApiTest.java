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
import java.util.UUID;
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
 * Workflows by issue type (ADR-20, B): a second workflow in a project, copied from the default, edited and mapped to a
 * type; the issues of the type follow it, a status code keeps one category in the project, and an issue whose type
 * changes workflow keeps or changes its status as B.5 says. Users of {@code project/users.sql}: mario admin of ODT,
 * luisa developer.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class WorkflowSchemeApiTest {

    private static final String ORIGIN = "http://localhost:8090";
    private static final String WORKFLOWS = "/api/projects/ODT/workflows";
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
    Workflows workflows;

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
                + "odt_workflow.workflow, odt_workflow.workflow_scheme, odt_agile.board, odt_agile.board_column CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        issue("Login fails", "bug");
        issue("Write the guide", "task");
    }

    private void issue(String summary, String type) throws Exception {
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa)
                        .content("{\"summary\":\"" + summary + "\",\"type\":\"" + type + "\"}"))
                .andExpect(status().isCreated());
    }

    private String read(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(as(request, luisa)).andReturn().getResponse().getContentAsString();
    }

    private void call(MockHttpServletRequestBuilder request, String body, int expected) throws Exception {
        mvc.perform(as(request, mario).content(body)).andExpect(status().is(expected));
    }

    /** A new workflow, copied from the default; its id. */
    private String add(String name) throws Exception {
        String body = mvc.perform(as(post(WORKFLOWS), mario).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private int version(String workflow) throws Exception {
        return JsonPath.read(read(get(WORKFLOWS + "/" + workflow)), "$.version");
    }

    private void types(String workflow, String types) throws Exception {
        call(put(WORKFLOWS + "/" + workflow), "{\"types\":" + types + ",\"version\":" + version(workflow) + "}", 200);
    }

    private void edit(String issue, String type) throws Exception {
        String body = read(get("/api/issues/" + issue));
        String summary = JsonPath.read(body, "$.summary");
        int version = JsonPath.read(body, "$.version");
        mvc.perform(as(put("/api/issues/" + issue), luisa)
                        .content("{\"summary\":\"" + summary + "\",\"type\":\"" + type + "\",\"version\":" + version + "}"))
                .andExpect(status().isOk());
    }

    private void move(String issue, String transition) throws Exception {
        int version = JsonPath.read(read(get("/api/issues/" + issue)), "$.version");
        mvc.perform(as(post("/api/issues/" + issue + "/transitions"), luisa)
                        .content("{\"transition\":\"" + transition + "\",\"version\":" + version + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void aTypeFollowsTheWorkflowItIsMappedTo() throws Exception {
        mvc.perform(as(get(WORKFLOWS), luisa))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].default").value(true))
                .andExpect(jsonPath("$[0].types").value(contains("task", "bug", "story", "epic")))
                .andExpect(jsonPath("$[0]._perm.remove").value(false));
        mvc.perform(as(post(WORKFLOWS), luisa).content("{\"name\":\"Bugs\"}")).andExpect(status().isForbidden());
        String bugs = add("Bugs");
        call(post(WORKFLOWS), "{\"name\":\"bugs\"}", 409);
        call(post(WORKFLOWS), "{\"name\":\" \"}", 400);
        mvc.perform(as(get(WORKFLOWS + "/" + bugs), luisa))
                .andExpect(jsonPath("$.name").value("Bugs"))
                .andExpect(jsonPath("$.types.length()").value(0))
                .andExpect(jsonPath("$.statuses").value(3))
                .andExpect(jsonPath("$.transitions").value(3));

        // Bugs start in triage, accepted into to do.
        String statuses = WORKFLOWS + "/" + bugs + "/statuses";
        call(post(statuses), "{\"code\":\"triage\",\"category\":\"todo\",\"position\":1}", 201);
        call(post(WORKFLOWS + "/" + bugs + "/transitions"), "{\"code\":\"accept\",\"from\":\"triage\",\"to\":\"todo\"}", 201);
        // A code keeps its category in every workflow of the project.
        call(post("/api/projects/ODT/workflow/statuses"), "{\"code\":\"triage\",\"category\":\"done\"}", 400);
        call(put(statuses + "/done"), "{\"category\":\"in_progress\",\"version\":" + version(bugs) + "}", 400);

        // The default serves the types no other workflow maps; it keeps them.
        String fallback = JsonPath.read(read(get(WORKFLOWS)), "$[0].id");
        call(put(WORKFLOWS + "/" + fallback), "{\"types\":[\"task\"],\"version\":" + version(fallback) + "}", 400);
        call(put(WORKFLOWS + "/" + bugs), "{\"types\":[\"incident\"],\"version\":" + version(bugs) + "}", 400);
        types(bugs, "[\"bug\"]");
        mvc.perform(as(get(WORKFLOWS), luisa))
                .andExpect(jsonPath("$[0].types").value(contains("task", "story", "epic")))
                .andExpect(jsonPath("$[1].types").value(contains("bug")))
                .andExpect(jsonPath("$[1]._perm.remove").value(false));
        // ODT-1, a bug in to do, stays there: Bugs has it.
        mvc.perform(as(get("/api/issues/ODT-1"), luisa)).andExpect(jsonPath("$.status").value("todo"));

        // A new bug starts in triage, a new task in to do; each sees the transitions of its workflow.
        issue("Crash on save", "bug");
        issue("Plan the release", "task");
        mvc.perform(as(get("/api/issues/ODT-3"), luisa)).andExpect(jsonPath("$.status").value("triage"));
        mvc.perform(as(get("/api/issues/ODT-4"), luisa)).andExpect(jsonPath("$.status").value("todo"));
        mvc.perform(as(get("/api/issues/ODT-3/transitions"), luisa)).andExpect(jsonPath("$[*].code").value(contains("accept")));
        mvc.perform(as(get("/api/issues/ODT-4/transitions"), luisa)).andExpect(jsonPath("$[*].code").value(contains("start")));
        mvc.perform(as(post("/api/issues/ODT-4/transitions"), luisa).content("{\"transition\":\"accept\",\"version\":0}"))
                .andExpect(status().isBadRequest());

        // The statuses of a workflow count the issues that follow it; the project sees them all, the default first.
        mvc.perform(as(get("/api/projects/ODT/workflow/statuses"), luisa)).andExpect(jsonPath("$[0].issues").value(2));
        mvc.perform(as(get(statuses), luisa))
                .andExpect(jsonPath("$[*].code").value(contains("triage", "todo", "in_progress", "done")))
                .andExpect(jsonPath("$[0].issues").value(1))
                .andExpect(jsonPath("$[1].issues").value(1));
        UUID project = jdbc.queryForObject("SELECT id FROM odt_project.project WHERE project_key = 'ODT'", UUID.class);
        assertThat(workflows.statuses(project)).extracting(Workflows.Status::code)
                .containsExactly("todo", "in_progress", "done", "triage");
    }

    @Test
    void anIssueThatChangesWorkflowKeepsOrChangesItsStatus() throws Exception {
        String bugs = add("Bugs");
        String statuses = WORKFLOWS + "/" + bugs + "/statuses";
        call(post(statuses), "{\"code\":\"fixing\",\"category\":\"in_progress\",\"position\":2}", 201);
        call(post(statuses + "/in_progress/remove"), "{\"moveTo\":\"fixing\",\"version\":" + version(bugs) + "}", 200);
        call(post(WORKFLOWS + "/" + bugs + "/transitions"), "{\"code\":\"fix\",\"from\":\"todo\",\"to\":\"fixing\"}", 201);
        types(bugs, "[\"bug\"]");

        // A task in progress becomes a bug: Bugs has no in_progress, so the first status of that category, fixing.
        move("ODT-2", "start");
        edit("ODT-2", "bug");
        mvc.perform(as(get("/api/issues/ODT-2"), luisa)).andExpect(jsonPath("$.status").value("fixing"));
        // And a task again: back to in_progress.
        edit("ODT-2", "task");
        mvc.perform(as(get("/api/issues/ODT-2"), luisa)).andExpect(jsonPath("$.status").value("in_progress"));
        TestInfrastructure.awaitListeners(jdbc);
        assertThat(jdbc.queryForList("SELECT field || ' ' || old_value || ' ' || new_value FROM odt_audit.issue_change c "
                + "JOIN odt_issue.issue i ON i.id = c.issue_id WHERE i.number = 2 AND field IN ('type', 'status') AND old_value IS NOT NULL "
                + "ORDER BY changed_at, field DESC", String.class))
                .containsExactly("status todo in_progress", "type task bug", "status in_progress fixing",
                        "type bug task", "status fixing in_progress");

        // A bug in fixing, when Bugs no longer serves bugs, goes to the default: in_progress.
        move("ODT-1", "fix");
        call(delete(WORKFLOWS + "/" + bugs).param("version", String.valueOf(version(bugs))), "", 409);
        types(bugs, "[]");
        mvc.perform(as(get("/api/issues/ODT-1"), luisa)).andExpect(jsonPath("$.status").value("in_progress"));

        // A workflow that serves no type is removed; the default stays.
        String fallback = JsonPath.read(read(get(WORKFLOWS)), "$[0].id");
        call(delete(WORKFLOWS + "/" + fallback).param("version", String.valueOf(version(fallback))), "", 409);
        call(delete(WORKFLOWS + "/" + bugs).param("version", "0"), "", 412);
        call(delete(WORKFLOWS + "/" + bugs).param("version", String.valueOf(version(bugs))), "", 204);
        mvc.perform(as(get(WORKFLOWS), luisa)).andExpect(jsonPath("$.length()").value(1));
        call(put(WORKFLOWS + "/" + fallback), "{\"name\":\"Standard\",\"version\":" + version(fallback) + "}", 200);
        mvc.perform(as(get(WORKFLOWS), luisa)).andExpect(jsonPath("$[0].name").value("Standard"));
    }
}
