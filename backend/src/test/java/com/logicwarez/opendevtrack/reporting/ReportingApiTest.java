/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
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
 * The read model of the reports and the reports themselves (ADR-17), through a sequence of events: issues created and
 * planned, a sprint started, an issue moved to done, the sprint completed, the issue reopened. Users of
 * {@code project/users.sql}: mario admin of ODT and WEB, luisa developer of ODT only, ugo of another tenant. Days in UTC.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class ReportingApiTest {

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

    @Autowired
    ReportModel model;

    MockMvc mvc;

    private final SessionAuthentication mario = session("mario", "ALPHA", CREATOR);
    private final SessionAuthentication luisa = session("luisa", "ALPHA", MEMBER);
    private final SessionAuthentication ugo = session("ugo", "BETA", CREATOR);
    private final LocalDate today = LocalDate.now(ZoneOffset.UTC);

    private static SessionAuthentication session(String username, String tenant, List<String> permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(username, username, username + "@example.org", tenant,
                List.of(new SessionContext.Tenant(tenant, tenant)), List.of(), permissions, now, now.plus(10, ChronoUnit.HOURS)));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, SessionAuthentication who) {
        return request.with(authentication(who)).with(csrf()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_agile.issue_plan, "
                + "odt_agile.sprint, odt_agile.sprint_issue, odt_agile.board, odt_agile.board_column, "
                + "odt_search.issue_doc, odt_search.filter, odt_reporting.issue_state, odt_reporting.daily_snapshot, "
                + "odt_reporting.sprint_result CASCADE");
        for (String project : List.of("ODT", "WEB")) {
            mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"" + project + "\",\"name\":\"" + project + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(as(post("/api/projects"), ugo).content("{\"key\":\"ODT\",\"name\":\"Other\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        issue("{\"summary\":\"Login fails\",\"type\":\"bug\",\"priority\":\"highest\",\"assignee\":\"luisa\"}");
        issue("{\"summary\":\"Checkout\",\"type\":\"story\"}");
        issue("{\"summary\":\"Banner\",\"type\":\"task\"}");
        points("ODT-1", 5);
        points("ODT-2", 3);
        points("ODT-3", 2);
        await(() -> count("SELECT count(*) FROM odt_reporting.issue_state WHERE points IS NOT NULL") == 3);
    }

    private void issue(String body) throws Exception {
        mvc.perform(as(post("/api/projects/ODT/issues"), mario).content(body)).andExpect(status().isCreated());
    }

    private void points(String key, int points) throws Exception {
        mvc.perform(as(put("/api/issues/" + key + "/plan"), mario).content("{\"points\":" + points + "}"))
                .andExpect(status().isOk());
    }

    private void transition(String key, String transition) throws Exception {
        String body = mvc.perform(as(get("/api/issues/" + key), mario)).andReturn().getResponse().getContentAsString();
        long version = ((Number) JsonPath.read(body, "$.version")).longValue();
        mvc.perform(as(post("/api/issues/" + key + "/transitions"), mario)
                        .content("{\"transition\":\"" + transition + "\",\"version\":" + version + "}"))
                .andExpect(status().isOk());
    }

    private void moveTo(String key, String column) throws Exception {
        mvc.perform(as(post("/api/projects/ODT/backlog/cards/" + key + "/move"), mario)
                .content("{\"column\":\"" + column + "\",\"beforeId\":null,\"afterId\":null}")).andExpect(status().isOk());
    }

    private String label() {
        return today.toString().substring(5);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private String state(String key, String column) {
        return jdbc.queryForObject("SELECT s." + column + "::text FROM odt_reporting.issue_state s "
                + "JOIN odt_issue.issue i ON i.id = s.issue_id JOIN odt_project.project p ON p.id = i.project_id "
                + "WHERE p.tenant_id = 'ALPHA' AND p.project_key || '-' || i.number = ?", String.class, key);
    }

    private Object read(SessionAuthentication who, String url, String path) throws Exception {
        String body = mvc.perform(as(get(url), who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, path);
    }

    /** The read model is written after the commit, on another thread: wait until it shows what is expected. */
    private static void await(BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 100 && !done.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(done.getAsBoolean()).isTrue();
    }

    @Test
    void theReportsFollowTheIssuesAndTheSprint() throws Exception {
        String reports = "/api/projects/ODT/reports/";
        String sprint = JsonPath.read(mvc.perform(as(post("/api/projects/ODT/sprints"), mario).content("{}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        moveTo("ODT-1", sprint);
        moveTo("ODT-2", sprint);
        mvc.perform(as(post("/api/sprints/" + sprint + "/start"), mario)
                        .content("{\"startsOn\":\"" + today.minusDays(2) + "\",\"endsOn\":\"" + today.plusDays(7) + "\"}"))
                .andExpect(status().isOk());
        await(() -> count("SELECT count(*) FROM odt_reporting.sprint_result WHERE committed_points = 8") == 1);

        // ODT-1 is started and finished today: in progress, then done, with its days.
        transition("ODT-1", "start");
        transition("ODT-1", "finish");
        await(() -> today.toString().equals(state("ODT-1", "resolved_on")));
        assertThat(state("ODT-1", "started_on")).isEqualTo(today.toString());
        assertThat(state("ODT-1", "category")).isEqualTo("done");

        mvc.perform(as(get(reports + "summary"), luisa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(2))
                .andExpect(jsonPath("$.inProgress").value(0))
                .andExpect(jsonPath("$.resolved").value(1))
                .andExpect(jsonPath("$.cycleTime").value(0.0))
                .andExpect(jsonPath("$.sprint").value("ODT Sprint 1"));
        // Burndown: from the 8 points committed two days ago to the 3 left today; the ideal line to zero at the end.
        List<Map<String, Object>> burndown = JsonPath.read(mvc.perform(as(get(reports + "burndown"), luisa))
                .andReturn().getResponse().getContentAsString(), "$");
        assertThat(burndown).hasSize(10);
        assertThat(burndown.get(0)).containsEntry("remaining", 8).containsEntry("ideal", 8.0);
        assertThat(burndown.get(2)).containsEntry("day", today.toString()).containsEntry("remaining", 3);
        assertThat(burndown.get(3)).containsEntry("remaining", null);
        assertThat(burndown.get(9)).containsEntry("ideal", 0.0);
        // Cumulative flow and created vs resolved: 30 days, today last.
        assertThat((List<?>) read(luisa, reports + "flow", "$")).hasSize(30);
        assertThat(read(luisa, reports + "flow", "$[29]"))
                .isEqualTo(Map.of("day", today.toString(), "label", label(), "todo", 2, "inProgress", 0, "done", 1));
        assertThat(read(luisa, reports + "created-resolved", "$[29]"))
                .isEqualTo(Map.of("day", today.toString(), "label", label(), "created", 3, "resolved", 1));

        // Complete: velocity has the sprint; its burndown ends with the points carried.
        mvc.perform(as(post("/api/sprints/" + sprint + "/complete"), mario).content("{\"moveTo\":\"backlog\"}"))
                .andExpect(status().isOk());
        await(() -> count("SELECT count(*) FROM odt_reporting.sprint_result WHERE completed_at IS NOT NULL") == 1);
        assertThat(read(luisa, reports + "velocity", "$"))
                .isEqualTo(List.of(Map.of("sprint", "ODT Sprint 1", "committed", 8, "done", 5)));
        assertThat(read(luisa, reports + "burndown?sprint=" + sprint, "$[2].remaining")).isEqualTo(3);
        assertThat(read(luisa, reports + "sprints", "$[0].state")).isEqualTo("completed");
        // The issues not done left the sprint, in the read model too.
        await(() -> count("SELECT count(*) FROM odt_reporting.issue_state WHERE sprint_id IS NOT NULL") == 0);

        // Reopened: no longer resolved, still started.
        transition("ODT-1", "reopen");
        await(() -> state("ODT-1", "resolved_on") == null);
        assertThat(state("ODT-1", "started_on")).isEqualTo(today.toString());
        assertThat(read(luisa, reports + "summary", "$.open")).isEqualTo(3);
    }

    @Test
    void reportsAreForTheMembersOfTheProject() throws Exception {
        mvc.perform(as(get("/api/projects/WEB/reports/summary"), luisa)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/projects/NOPE/reports/summary"), luisa)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/projects/ODT/reports/burndown?sprint=x"), luisa)).andExpect(status().isBadRequest());
        // ugo's ODT is the project of his tenant, with no issues.
        assertThat(read(ugo, "/api/projects/ODT/reports/summary", "$.open")).isEqualTo(0);
        assertThat(read(mario, "/api/projects/ODT/reports/summary", "$.open")).isEqualTo(3);
        assertThat(read(mario, "/api/projects/ODT/reports/burndown", "$")).isEqualTo(List.of());
        assertThat(read(mario, "/api/projects/ODT/reports/velocity", "$")).isEqualTo(List.of());
    }

    @Test
    void theNightlyJobWritesTheDayOfEveryProject() throws Exception {
        jdbc.execute("DELETE FROM odt_reporting.daily_snapshot");
        model.nightly();
        assertThat(count("SELECT count(*) FROM odt_reporting.daily_snapshot WHERE sprint_id IS NULL AND day = current_date"))
                .isEqualTo(1);
        assertThat(count("SELECT todo FROM odt_reporting.daily_snapshot")).isEqualTo(3);
        assertThat(count("SELECT remaining_points FROM odt_reporting.daily_snapshot")).isEqualTo(10);
    }

    @Test
    void thePersonalDashboardCountsWhatTheUserSees() throws Exception {
        await(() -> count("SELECT count(*) FROM odt_search.issue_doc") == 3);
        mvc.perform(as(get("/api/me/summary"), luisa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned").value(1))
                .andExpect(jsonPath("$.reported").value(0))
                .andExpect(jsonPath("$.critical").value(1))
                .andExpect(jsonPath("$.resolved").value(0));
        mvc.perform(as(get("/api/me/summary"), mario)).andExpect(jsonPath("$.reported").value(3));
        mvc.perform(as(get("/api/me/summary"), ugo)).andExpect(jsonPath("$.critical").value(0));
        // Only the user's own filters marked for the dashboard, with what each finds.
        mvc.perform(as(post("/api/filters"), luisa)
                .content("{\"name\":\"Bugs\",\"query\":\"type = bug\",\"onDashboard\":true}")).andExpect(status().isCreated());
        mvc.perform(as(post("/api/filters"), luisa)
                .content("{\"name\":\"All\",\"query\":\"project = ODT\",\"onDashboard\":false}")).andExpect(status().isCreated());
        mvc.perform(as(post("/api/filters"), mario)
                .content("{\"name\":\"Shared\",\"query\":\"project = ODT\",\"shared\":true,\"onDashboard\":true}"))
                .andExpect(status().isCreated());
        mvc.perform(as(get("/api/me/filters"), luisa))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Bugs"))
                .andExpect(jsonPath("$[0].count").value(1));
    }
}
