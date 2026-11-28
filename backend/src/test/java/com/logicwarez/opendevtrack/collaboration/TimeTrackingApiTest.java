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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Time tracking (ADR-30): the estimates of an issue and its work log. Users of {@code project/users.sql}: mario admin of
 * ODT, luisa developer, paola viewer, ugo of another tenant. A day is 8 hours, a week 5 days.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class TimeTrackingApiTest {

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
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_audit.issue_change, "
                + "odt_collaboration.comment, odt_collaboration.worklog CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
        // The first original estimate is also what remains.
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("""
                        {"summary":"Login fails","type":"bug","originalEstimate":"1d 30m"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalEstimate").value("1d 30m"))
                .andExpect(jsonPath("$.originalEstimateMinutes").value(510))
                .andExpect(jsonPath("$.remainingEstimate").value("1d 30m"))
                .andExpect(jsonPath("$.timeSpent").value("0m"))
                .andExpect(jsonPath("$.timeSpentMinutes").value(0));
    }

    private String log(SessionAuthentication who, String body) throws Exception {
        String created = mvc.perform(as(post("/api/issues/ODT-1/worklogs"), who).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    private static void await(IntSupplier count, int expected) throws InterruptedException {
        for (int i = 0; i < 100 && count.getAsInt() < expected; i++) {
            Thread.sleep(50);
        }
        assertThat(count.getAsInt()).isEqualTo(expected);
    }

    @Test
    void workLoggedMovesTheTimeSpentAndWhatRemains() throws Exception {
        String id = log(luisa, """
                {"timeSpent":"2h","started":"2026-11-27T09:00","comment":"Found the cause"}""");
        mvc.perform(as(get("/api/issues/ODT-1"), paola))
                .andExpect(jsonPath("$.timeSpent").value("2h"))
                .andExpect(jsonPath("$.remainingEstimate").value("6h 30m"))
                .andExpect(jsonPath("$._perm.worklog").value(false));
        // A remaining estimate given sets it.
        log(mario, """
                {"timeSpent":"1h","remainingEstimate":"1h"}""");
        mvc.perform(as(get("/api/issues/ODT-1/worklogs"), luisa))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].timeSpent").value("2h"))
                .andExpect(jsonPath("$[0].minutes").value(120))
                .andExpect(jsonPath("$[0].author").value("luisa"))
                .andExpect(jsonPath("$[0].comment").value("Found the cause"))
                .andExpect(jsonPath("$[0].started").value("2026-11-27T09:00:00Z"))
                .andExpect(jsonPath("$[0]._perm.edit").value(true))
                .andExpect(jsonPath("$[1]._perm.edit").value(false));
        // Changed from 2h to 3h: one more hour spent, one less remaining, never below zero; a start with an offset is
        // the instant it names.
        mvc.perform(as(put("/api/issues/ODT-1/worklogs/" + id), luisa).content("""
                        {"timeSpent":"3h","started":"2026-11-27T10:30:00+01:00","comment":"Found and fixed","version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeSpent").value("3h"))
                .andExpect(jsonPath("$.started").value("2026-11-27T09:30:00Z"));
        mvc.perform(as(get("/api/issues/ODT-1"), paola))
                .andExpect(jsonPath("$.timeSpent").value("4h"))
                .andExpect(jsonPath("$.remainingEstimate").value("0m"));
        // Deleted: the time comes back to what remains.
        mvc.perform(as(delete("/api/issues/ODT-1/worklogs/" + id), luisa)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/issues/ODT-1"), paola))
                .andExpect(jsonPath("$.timeSpent").value("1h"))
                .andExpect(jsonPath("$.timeSpentMinutes").value(60))
                .andExpect(jsonPath("$.remainingEstimate").value("3h"));
    }

    @Test
    void onlyDevelopersLogAndOnlyAdminsChangeTheLogsOfOthers() throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/worklogs"), paola).content("{\"timeSpent\":\"1h\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/issues/ODT-1/worklogs"), ugo).content("{\"timeSpent\":\"1h\"}"))
                .andExpect(status().isNotFound());
        String marios = log(mario, "{\"timeSpent\":\"30m\"}");
        String luisas = log(luisa, "{\"timeSpent\":\"45m\"}");
        mvc.perform(as(put("/api/issues/ODT-1/worklogs/" + marios), luisa).content("{\"timeSpent\":\"1h\",\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/issues/ODT-1/worklogs/" + marios), luisa)).andExpect(status().isForbidden());
        mvc.perform(as(put("/api/issues/ODT-1/worklogs/" + luisas), mario).content("{\"timeSpent\":\"1h\",\"version\":0}"))
                .andExpect(status().isOk());
        // An old version, a wrong duration, no time at all.
        mvc.perform(as(put("/api/issues/ODT-1/worklogs/" + luisas), luisa).content("{\"timeSpent\":\"2h\",\"version\":0}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(post("/api/issues/ODT-1/worklogs"), luisa).content("{\"timeSpent\":\"2 hours\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/issues/ODT-1/worklogs"), luisa).content("{\"timeSpent\":\"0m\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/issues/ODT-1"), paola)).andExpect(jsonPath("$.timeSpent").value("1h 30m"));
    }

    @Test
    void theEstimatesAreFieldsWithTheirHistoryAndTheActivityHasTheWorkLog() throws Exception {
        String issue = mvc.perform(as(get("/api/issues/ODT-1"), luisa)).andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(issue, "$.version");
        mvc.perform(as(patch("/api/issues/ODT-1"), luisa).content("""
                        {"originalEstimate":"2d","remainingEstimate":"1w","version":%d}""".formatted(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalEstimate").value("2d"))
                .andExpect(jsonPath("$.remainingEstimate").value("1w"));
        mvc.perform(as(patch("/api/issues/ODT-1"), luisa).content("""
                        {"originalEstimate":"two days","version":%d}""".formatted(version + 1)))
                .andExpect(status().isBadRequest());
        log(luisa, "{\"timeSpent\":\"1h 30m\",\"comment\":\"Started\"}");
        // created, type, summary, priority, original and remaining estimate; then the patch (2), then the work (2).
        await(() -> jdbc.queryForObject("SELECT count(*) FROM odt_audit.issue_change", Integer.class), 10);
        mvc.perform(as(get("/api/issues/ODT-1/history"), paola))
                .andExpect(jsonPath("$[*].field").value(org.hamcrest.Matchers.hasItems("originalEstimate",
                        "remainingEstimate", "timeSpent")));
        mvc.perform(as(get("/api/issues/ODT-1/activity"), luisa))
                .andExpect(jsonPath("$[?(@.kind == 'worklog')].minutes").value(org.hamcrest.Matchers.contains(90)))
                .andExpect(jsonPath("$[?(@.kind == 'worklog')].text").value(org.hamcrest.Matchers.contains("Started")))
                .andExpect(jsonPath("$[?(@.field == 'timeSpent')].to").value(org.hamcrest.Matchers.contains("1h 30m")));
        mvc.perform(as(get("/api/time-tracking"), paola))
                .andExpect(jsonPath("$.hoursPerDay").value(8))
                .andExpect(jsonPath("$.daysPerWeek").value(5));
    }
}
