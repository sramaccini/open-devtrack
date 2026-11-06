/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

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
 * Boards, backlog and sprints over HTTP (ADR-15; users in {@code project/users.sql}): mario creates the project ODT and
 * is its admin, luisa is a developer, paola a viewer; ugo is in another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class AgileApiTest {

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
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_agile.issue_plan, "
                + "odt_agile.sprint, odt_agile.sprint_issue, odt_agile.board, odt_agile.board_column CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
        for (String summary : List.of("One", "Two", "Three", "Four")) {
            mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"" + summary + "\"}"))
                    .andExpect(status().isCreated());
        }
    }

    private String board() throws Exception {
        String body = mvc.perform(as(get("/api/projects/ODT/boards"), paola)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[0].id");
    }

    private List<String> keys(String url, SessionAuthentication who) throws Exception {
        String body = mvc.perform(as(get(url), who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].key");
    }

    private void move(String url, String column, String before, String after, SessionAuthentication who, int expected)
            throws Exception {
        String body = "{\"column\":\"" + column + "\",\"beforeId\":" + quoted(before) + ",\"afterId\":" + quoted(after) + "}";
        mvc.perform(as(post(url), who).content(body)).andExpect(status().is(expected));
    }

    private static String quoted(String text) {
        return text == null ? "null" : "\"" + text + "\"";
    }

    @Test
    void aNewProjectHasABoardWithAColumnPerStatus() throws Exception {
        String board = board();
        mvc.perform(as(get("/api/boards"), paola))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].project").value("ODT"))
                .andExpect(jsonPath("$[0].kind").value("kanban"))
                .andExpect(jsonPath("$[0]._perm.edit").value(false));
        mvc.perform(as(get("/api/boards/" + board + "/columns"), paola).header("Accept-Language", "it"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].value").value("0"))
                .andExpect(jsonPath("$[0].label").value("Da fare"))
                .andExpect(jsonPath("$[2].label").value("Fatta"));
        // The issues in the order they were created, all in the first column.
        assertThat(keys("/api/boards/" + board + "/cards", paola)).containsExactly("ODT-1", "ODT-2", "ODT-3", "ODT-4");
        mvc.perform(as(get("/api/boards/" + board + "/cards"), paola))
                .andExpect(jsonPath("$[0].column").value("0"))
                .andExpect(jsonPath("$[0]._perm.move").value(false));
        mvc.perform(as(get("/api/boards/" + board), luisa)).andExpect(jsonPath("$._perm.move").value(true));
    }

    @Test
    void aMoveToAnotherColumnRunsATransition() throws Exception {
        String board = board();
        String cards = "/api/boards/" + board + "/cards/";
        // To do → In progress: the transition start; between nothing, so the place stays.
        move(cards + "ODT-2/move", "1", null, null, luisa, 200);
        mvc.perform(as(get("/api/issues/ODT-2"), paola)).andExpect(jsonPath("$.status").value("in_progress"));
        mvc.perform(as(get("/api/issues/ODT-2/history"), paola)).andExpect(jsonPath("$[0].field").value("status"));
        // No transition goes from To do to Done.
        move(cards + "ODT-1/move", "2", null, null, luisa, 409);
        // A viewer moves nothing; a column that is not there is refused.
        move(cards + "ODT-3/move", "1", null, null, paola, 403);
        move(cards + "ODT-3/move", "7", null, null, luisa, 400);
        // A guard decides as on the issue: start only with an assignee.
        mvc.perform(as(put("/api/projects/ODT/workflow/transitions/start"), mario).content("{\"guard\":\"issue.assignee != null\"}"))
                .andExpect(status().isOk());
        move(cards + "ODT-3/move", "1", null, null, luisa, 409);
    }

    @Test
    void aMoveWithinAColumnChangesTheOrder() throws Exception {
        String board = board();
        String cards = "/api/boards/" + board + "/cards/";
        move(cards + "ODT-4/move", "0", null, "ODT-1", luisa, 200);
        assertThat(keys("/api/boards/" + board + "/cards", paola)).containsExactly("ODT-4", "ODT-1", "ODT-2", "ODT-3");
        move(cards + "ODT-1/move", "0", "ODT-3", null, luisa, 200);
        move(cards + "ODT-2/move", "0", "ODT-4", "ODT-3", luisa, 200);
        assertThat(keys("/api/boards/" + board + "/cards", paola)).containsExactly("ODT-4", "ODT-2", "ODT-3", "ODT-1");
        // The backlog shows the same order.
        assertThat(keys("/api/projects/ODT/backlog/cards", paola)).containsExactly("ODT-4", "ODT-2", "ODT-3", "ODT-1");
        move(cards + "ODT-1/move", "0", null, "ODT-4", paola, 403);
        // A new issue goes to the bottom.
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Five\"}")).andExpect(status().isCreated());
        assertThat(keys("/api/boards/" + board + "/cards", paola)).last().isEqualTo("ODT-5");
    }

    @Test
    void theAdminsManageTheBoards() throws Exception {
        String scrum = """
                {"name":"Team","kind":"scrum","lane":"assignee","columns":"Open: todo\\nDoing: in_progress | 2\\nDone: done"}""";
        mvc.perform(as(post("/api/projects/ODT/boards"), luisa).content(scrum)).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/projects/ODT/boards"), mario).content(scrum.replace("in_progress", "review")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/boards"), mario).content(scrum.replace("Done: done", "Done: done, todo")))
                .andExpect(status().isBadRequest());
        String body = mvc.perform(as(post("/api/projects/ODT/boards"), mario).content(scrum))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.columns").value("Open: todo\nDoing: in_progress | 2\nDone: done"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        mvc.perform(as(get("/api/boards/" + id + "/columns"), paola))
                .andExpect(jsonPath("$[1].label").value("Doing"))
                .andExpect(jsonPath("$[1].limit").value(2));
        // A scrum board without an active sprint is empty.
        assertThat(keys("/api/boards/" + id + "/cards", paola)).isEmpty();
        mvc.perform(as(put("/api/boards/" + id), mario).content("{\"name\":\"Team\",\"kind\":\"kanban\",\"lane\":\"none\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns").value(""));
        mvc.perform(as(put("/api/boards/" + id), mario).content("{\"name\":\"Old\",\"version\":0}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(delete("/api/boards/" + id), mario)).andExpect(status().isNoContent());
        mvc.perform(as(delete("/api/boards/" + board()), mario)).andExpect(status().isConflict());
    }

    @Test
    void aSprintIsPlannedStartedAndCompleted() throws Exception {
        String body = mvc.perform(as(post("/api/projects/ODT/sprints"), luisa).content("{\"goal\":\"Login\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("ODT Sprint 1"))
                .andExpect(jsonPath("$.state").value("planned"))
                .andReturn().getResponse().getContentAsString();
        String sprint = JsonPath.read(body, "$.id");
        mvc.perform(as(post("/api/projects/ODT/sprints"), paola).content("{}")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/projects/ODT/backlog/columns"), paola))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].value").value(sprint))
                .andExpect(jsonPath("$[1].value").value("backlog"));
        // Plan ODT-1 and ODT-3 into the sprint, ODT-3 first; points on ODT-1.
        String backlog = "/api/projects/ODT/backlog/cards/";
        move(backlog + "ODT-1/move", sprint, null, null, luisa, 200);
        move(backlog + "ODT-3/move", sprint, null, "ODT-1", luisa, 200);
        move(backlog + "ODT-2/move", sprint, null, null, paola, 403);
        mvc.perform(as(put("/api/issues/ODT-1/plan"), luisa).content("{\"points\":5}")).andExpect(status().isOk());
        mvc.perform(as(put("/api/issues/ODT-1/plan"), luisa).content("{\"points\":1000}")).andExpect(status().isBadRequest());
        mvc.perform(as(put("/api/issues/ODT-1/plan"), paola).content("{\"points\":3}")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/projects/ODT/backlog/cards"), paola))
                .andExpect(jsonPath("$[0].key").value("ODT-3"))
                .andExpect(jsonPath("$[0].column").value(sprint))
                .andExpect(jsonPath("$[1].points").value(5))
                .andExpect(jsonPath("$[2].column").value("backlog"));
        mvc.perform(as(get("/api/issues/ODT-1/plan"), paola))
                .andExpect(jsonPath("$.points").value(5))
                .andExpect(jsonPath("$.sprint").value("ODT Sprint 1"))
                .andExpect(jsonPath("$._perm.edit").value(false));

        // Start: a scrum board shows the sprint; a second sprint cannot start too.
        mvc.perform(as(post("/api/sprints/" + sprint + "/start"), luisa).content("{\"startsOn\":\"2026-11-09\",\"endsOn\":\"2026-11-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("active"))
                .andExpect(jsonPath("$.issues").value(2))
                .andExpect(jsonPath("$.points").value(5));
        String next = JsonPath.read(mvc.perform(as(post("/api/projects/ODT/sprints"), luisa).content("{}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(as(post("/api/sprints/" + next + "/start"), luisa)).andExpect(status().isConflict());
        String scrum = JsonPath.read(mvc.perform(as(post("/api/projects/ODT/boards"), mario)
                .content("{\"name\":\"Sprint\",\"kind\":\"scrum\"}")).andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(keys("/api/boards/" + scrum + "/cards", paola)).containsExactly("ODT-3", "ODT-1");
        mvc.perform(as(get("/api/boards/" + scrum), paola)).andExpect(jsonPath("$.sprint").value("ODT Sprint 1"));
        // ODT-2 joins while active; ODT-3 leaves; ODT-1 is done.
        move(backlog + "ODT-2/move", sprint, null, null, luisa, 200);
        move(backlog + "ODT-3/move", "backlog", null, null, luisa, 200);
        move("/api/boards/" + scrum + "/cards/ODT-1/move", "1", null, null, luisa, 200);
        move("/api/boards/" + scrum + "/cards/ODT-1/move", "2", null, null, luisa, 200);

        // Complete: the issue done stays with the sprint, ODT-2 goes to the next one.
        mvc.perform(as(post("/api/sprints/" + sprint + "/complete"), luisa).content("{\"moveTo\":\"nope\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/sprints/" + sprint + "/complete"), luisa).content("{\"moveTo\":\"next\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"));
        assertThat(jdbc.queryForList("SELECT outcome FROM odt_agile.sprint_issue ORDER BY added_at, outcome", String.class))
                .containsExactly("done", "removed", "carried");
        mvc.perform(as(get("/api/projects/ODT/backlog/columns"), paola)).andExpect(jsonPath("$[0].value").value(next));
        mvc.perform(as(get("/api/projects/ODT/backlog/cards"), paola))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.key == 'ODT-2')].column").value(next));
        mvc.perform(as(get("/api/projects/ODT/sprints"), paola))
                .andExpect(jsonPath("$[0].state").value("planned"))
                .andExpect(jsonPath("$[0]._perm.start").value(false))
                .andExpect(jsonPath("$[1].state").value("completed"));
        // A planned sprint is removed, and its issues go back to the backlog.
        mvc.perform(as(delete("/api/sprints/" + sprint), luisa)).andExpect(status().isConflict());
        mvc.perform(as(delete("/api/sprints/" + next), luisa)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/issues/ODT-2/plan"), paola)).andExpect(jsonPath("$.sprint").doesNotExist());
    }

    @Test
    void anotherTenantSeesNothing() throws Exception {
        String board = board();
        mvc.perform(as(get("/api/boards/" + board), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/boards/" + board + "/cards"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/projects/ODT/backlog/cards"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/projects/ODT/sprints"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/issues/ODT-1/plan"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/boards"), ugo)).andExpect(jsonPath("$.length()").value(0));
    }
}
