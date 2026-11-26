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
        TestInfrastructure.awaitListeners(jdbc);
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

    /** The rows of the backlog screen (ADR-26): its query on the search, once the index has the last changes. */
    private String backlog(SessionAuthentication who) throws Exception {
        TestInfrastructure.awaitListeners(jdbc);
        return mvc.perform(as(post("/api/search"), who).content("""
                        {"query":"project = ODT AND (sprint IN openSprints() OR (sprint IS EMPTY AND category != done)) \
                        ORDER BY rank"}"""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static List<String> keys(String rows) {
        return JsonPath.read(rows, "$.items[*].key");
    }

    private void to(String url, String end, SessionAuthentication who, int expected) throws Exception {
        mvc.perform(as(post(url), who).content("{\"to\":\"" + end + "\"}")).andExpect(status().is(expected));
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
    void aFilterKeepsTheIssuesItFinds() throws Exception {
        // The filter runs on the index of the search, written after the commit.
        TestInfrastructure.awaitListeners(jdbc);
        mvc.perform(as(post("/api/projects/ODT/boards"), mario).content("{\"name\":\"Wrong\",\"filter\":\"status = \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value(org.hamcrest.Matchers.startsWith("The filter is not right at position")))
                .andExpect(jsonPath("$.position").isNumber());
        String created = mvc.perform(as(post("/api/projects/ODT/boards"), mario).content("""
                        {"name":"Two and four","filter":"summary ~ Two OR summary ~ Four"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.filter").value("summary ~ Two OR summary ~ Four"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        assertThat(keys("/api/boards/" + id + "/cards", paola)).containsExactly("ODT-2", "ODT-4");
        mvc.perform(as(get("/api/boards/" + id), paola)).andExpect(jsonPath("$.filter").value("summary ~ Two OR summary ~ Four"));

        // currentUser() is the user who looks at the board: luisa reported every issue, paola none.
        mvc.perform(as(put("/api/boards/" + id), mario).content("""
                        {"name":"Reported by me","filter":"reporter = currentUser()","version":0}"""))
                .andExpect(status().isOk());
        assertThat(keys("/api/boards/" + id + "/cards", luisa)).containsExactly("ODT-1", "ODT-2", "ODT-3", "ODT-4");
        assertThat(keys("/api/boards/" + id + "/cards", paola)).isEmpty();

        // An empty filter: every issue again.
        mvc.perform(as(put("/api/boards/" + id), mario).content("{\"name\":\"All\",\"filter\":\" \",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter").doesNotExist());
        assertThat(keys("/api/boards/" + id + "/cards", paola)).hasSize(4);
    }

    @Test
    void aMoveToAnotherColumnRunsATransition() throws Exception {
        String board = board();
        String cards = "/api/boards/" + board + "/cards/";
        // To do → In progress: the transition start; between nothing, so the place stays.
        move(cards + "ODT-2/move", "1", null, null, luisa, 200);
        mvc.perform(as(get("/api/issues/ODT-2"), paola)).andExpect(jsonPath("$.status").value("in_progress"));
        // The history is written after the commit, on another thread.
        TestInfrastructure.awaitListeners(jdbc);
        mvc.perform(as(get("/api/issues/ODT-2/history"), paola)).andExpect(jsonPath("$[0].field").value("status"));
        // No transition goes from To do to Done.
        move(cards + "ODT-1/move", "2", null, null, luisa, 409);
        // A viewer moves nothing; a column that is not there is refused.
        move(cards + "ODT-3/move", "1", null, null, paola, 403);
        move(cards + "ODT-3/move", "7", null, null, luisa, 400);
        // A guard decides as on the issue: start only with an assignee.
        mvc.perform(as(put("/api/projects/ODT/workflow/transitions/start"), mario)
                        .content("{\"from\":\"todo\",\"to\":\"in_progress\",\"guard\":\"issue.assignee != null\",\"version\":0}"))
                .andExpect(status().isOk());
        move(cards + "ODT-3/move", "1", null, null, luisa, 409);
    }

    /** A kanban board of the project with lanes by {@code lane}, and the path of its cards. */
    private String laneBoard(String lane) throws Exception {
        String body = mvc.perform(as(post("/api/projects/ODT/boards"), mario)
                        .content("{\"name\":\"By " + lane + "\",\"kind\":\"kanban\",\"lane\":\"" + lane + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "/api/boards/" + JsonPath.read(body, "$.id") + "/cards/";
    }

    private void moveToLane(String url, String column, String lane, SessionAuthentication who, int expected)
            throws Exception {
        mvc.perform(as(post(url), who).content("{\"column\":\"" + column + "\",\"lane\":" + quoted(lane) + "}"))
                .andExpect(status().is(expected));
    }

    @Test
    void aMoveToAnotherLaneChangesItsField() throws Exception {
        String byPriority = laneBoard("priority");
        moveToLane(byPriority + "ODT-1/move", "0", "high", luisa, 200);
        mvc.perform(as(get("/api/issues/ODT-1"), paola))
                .andExpect(jsonPath("$.priority").value("high"))
                .andExpect(jsonPath("$.status").value("todo"));
        TestInfrastructure.awaitListeners(jdbc);
        mvc.perform(as(get("/api/issues/ODT-1/history"), paola)).andExpect(jsonPath("$[0].field").value("priority"));
        // A value that is not a priority, and a viewer, change nothing.
        moveToLane(byPriority + "ODT-1/move", "0", "urgent", luisa, 400);
        moveToLane(byPriority + "ODT-1/move", "0", "low", paola, 403);
        // The lane the issue is already in changes nothing.
        moveToLane(byPriority + "ODT-1/move", "0", "high", luisa, 200);

        String byType = laneBoard("type");
        moveToLane(byType + "ODT-2/move", "0", "bug", luisa, 200);
        mvc.perform(as(get("/api/issues/ODT-2"), paola)).andExpect(jsonPath("$.type").value("bug"));

        // Lanes by nothing: the lane of the request is not a field.
        moveToLane("/api/boards/" + board() + "/cards/ODT-3/move", "0", "highest", luisa, 200);
        mvc.perform(as(get("/api/issues/ODT-3"), paola)).andExpect(jsonPath("$.priority").value("medium"));
    }

    @Test
    void aMoveToAnotherAssigneeFollowsTheRulesOfTheIssue() throws Exception {
        String cards = laneBoard("assignee");
        moveToLane(cards + "ODT-1/move", "0", "luisa", luisa, 200);
        mvc.perform(as(get("/api/issues/ODT-1"), paola)).andExpect(jsonPath("$.assignee").value("luisa"));
        // The new assignee watches the issue, as from the form.
        mvc.perform(as(get("/api/issues/ODT-1/watchers"), paola)).andExpect(jsonPath("$[?(@.username == 'luisa')]").exists());
        // The lane of nobody takes the assignee away; one who is not a member is refused.
        moveToLane(cards + "ODT-1/move", "0", "", luisa, 200);
        mvc.perform(as(get("/api/issues/ODT-1"), paola)).andExpect(jsonPath("$.assignee").doesNotExist());
        moveToLane(cards + "ODT-1/move", "0", "ugo", luisa, 400);
        // The reviewer cannot be the assignee.
        mvc.perform(as(put("/api/issues/ODT-2"), luisa).content("{\"summary\":\"Two\",\"reviewer\":\"luisa\",\"version\":0}"))
                .andExpect(status().isOk());
        moveToLane(cards + "ODT-2/move", "0", "luisa", luisa, 400);

        // Another lane and another column: one change, whose transition sees the new assignee.
        mvc.perform(as(put("/api/projects/ODT/workflow/transitions/start"), mario)
                        .content("{\"from\":\"todo\",\"to\":\"in_progress\",\"guard\":\"issue.assignee == 'mario'\",\"version\":0}"))
                .andExpect(status().isOk());
        moveToLane(cards + "ODT-3/move", "1", "luisa", luisa, 409);
        mvc.perform(as(get("/api/issues/ODT-3"), paola))
                .andExpect(jsonPath("$.assignee").doesNotExist())
                .andExpect(jsonPath("$.status").value("todo"));
        moveToLane(cards + "ODT-3/move", "1", "mario", luisa, 200);
        mvc.perform(as(get("/api/issues/ODT-3"), paola))
                .andExpect(jsonPath("$.assignee").value("mario"))
                .andExpect(jsonPath("$.status").value("in_progress"));
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
        assertThat(keys(backlog(paola))).containsExactly("ODT-4", "ODT-2", "ODT-3", "ODT-1");
        move(cards + "ODT-1/move", "0", null, "ODT-4", paola, 403);
        // A new issue goes to the bottom.
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Five\"}")).andExpect(status().isCreated());
        assertThat(keys("/api/boards/" + board + "/cards", paola)).last().isEqualTo("ODT-5");
    }

    @Test
    void anIssueGoesToTheTopOrTheBottomOfTheOrder() throws Exception {
        String backlog = "/api/projects/ODT/backlog/cards/";
        to(backlog + "ODT-3/move", "top", luisa, 200);
        to(backlog + "ODT-1/move", "bottom", luisa, 200);
        to(backlog + "ODT-4/move", "bottom", luisa, 200);
        String rows = backlog(luisa);
        assertThat(keys(rows)).containsExactly("ODT-3", "ODT-2", "ODT-1", "ODT-4");
        // The rows say who may plan and order them: a developer, not a viewer.
        assertThat(JsonPath.<Boolean>read(rows, "$.items[0]._perm.plan")).isTrue();
        assertThat(JsonPath.<Boolean>read(rows, "$.items[0]._perm.rank")).isTrue();
        assertThat(JsonPath.<Boolean>read(backlog(paola), "$.items[0]._perm.rank")).isFalse();
        to(backlog + "ODT-2/move", "top", paola, 403);
        to(backlog + "ODT-2/move", "middle", luisa, 400);
    }

    @Test
    void theChosenIssuesGoToASprintTogether() throws Exception {
        String body = mvc.perform(as(post("/api/projects/ODT/sprints"), luisa).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String sprint = JsonPath.read(body, "$.id");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"WEB\",\"name\":\"Web\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/WEB/issues"), mario).content("{\"summary\":\"Elsewhere\"}"))
                .andExpect(status().isCreated());
        String plan = "/api/projects/ODT/backlog/plan";
        mvc.perform(as(post(plan), luisa).content("{\"issues\":[\"ODT-2\",\"ODT-4\",\"ODT-2\"],\"column\":\"" + sprint + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved").value(2));
        String rows = backlog(paola);
        assertThat(JsonPath.<List<String>>read(rows, "$.items[?(@.sprint == 'ODT Sprint 1')].key"))
                .containsExactly("ODT-2", "ODT-4");
        // All or none: an issue of another project, an unknown sprint, a viewer, nothing chosen.
        mvc.perform(as(post(plan), luisa).content("{\"issues\":[\"ODT-1\",\"WEB-1\"],\"column\":\"" + sprint + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post(plan), luisa).content("{\"issues\":[\"ODT-1\"],\"column\":\"nowhere\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post(plan), paola).content("{\"issues\":[\"ODT-1\"],\"column\":\"backlog\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post(plan), luisa).content("{\"issues\":[],\"column\":\"backlog\"}"))
                .andExpect(status().isBadRequest());
        assertThat(JsonPath.<List<String>>read(backlog(paola), "$.items[?(@.sprint == 'ODT Sprint 1')].key"))
                .containsExactly("ODT-2", "ODT-4");
        // Back to the backlog: only those in a sprint count as moved.
        mvc.perform(as(post(plan), luisa).content("{\"issues\":[\"ODT-1\",\"ODT-2\",\"ODT-4\"],\"column\":\"backlog\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moved").value(2));
        assertThat(JsonPath.<List<String>>read(backlog(paola), "$.items[*].sprint")).containsOnly("Backlog");
        mvc.perform(as(post("/api/projects/ODT/backlog/plan"), ugo).content("{\"issues\":[\"ODT-1\"],\"column\":\"backlog\"}"))
                .andExpect(status().isNotFound());
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
        String planned = backlog(paola);
        assertThat(keys(planned)).containsExactly("ODT-3", "ODT-1", "ODT-2", "ODT-4");
        assertThat(JsonPath.<String>read(planned, "$.items[0].sprint")).isEqualTo("ODT Sprint 1");
        assertThat(JsonPath.<String>read(planned, "$.items[0].sprintState")).isEqualTo("planned");
        assertThat(JsonPath.<Integer>read(planned, "$.items[1].points")).isEqualTo(5);
        assertThat(JsonPath.<String>read(planned, "$.items[2].sprint")).isEqualTo("Backlog");
        mvc.perform(as(get("/api/issues/ODT-1/plan"), paola))
                .andExpect(jsonPath("$.points").value(5))
                .andExpect(jsonPath("$.sprint").value("ODT Sprint 1"))
                .andExpect(jsonPath("$.sprintId").value(sprint))
                .andExpect(jsonPath("$._perm.edit").value(false))
                .andExpect(jsonPath("$._perm.plan").value(false));
        // The value of its column in the backlog, for the sprint chosen on the page of the issue.
        mvc.perform(as(get("/api/issues/ODT-2/plan"), luisa))
                .andExpect(jsonPath("$.sprintId").value("backlog"))
                .andExpect(jsonPath("$._perm.plan").value(true));

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
        // Done in a completed sprint: it is in its history, no longer in a plan.
        mvc.perform(as(get("/api/issues/ODT-1/plan"), luisa))
                .andExpect(jsonPath("$.sprint").doesNotExist())
                .andExpect(jsonPath("$.sprintId").value("backlog"));
        String left = backlog(paola);
        assertThat(keys(left)).containsExactlyInAnyOrder("ODT-2", "ODT-3", "ODT-4");
        assertThat(JsonPath.<List<String>>read(left, "$.items[?(@.key == 'ODT-2')].sprint")).containsExactly("ODT Sprint 2");
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
        mvc.perform(as(post("/api/projects/ODT/backlog/cards/ODT-1/move"), ugo).content("{\"to\":\"top\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(as(get("/api/projects/ODT/sprints"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/issues/ODT-1/plan"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/boards"), ugo)).andExpect(jsonPath("$.length()").value(0));
    }
}
