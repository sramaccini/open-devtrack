/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

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
 * Teams (ADR-31): the admin of the tenant manages them, their lead their members, an issue names one, ODQL finds it,
 * and a team deleted leaves its issues without one. Users of {@code project/users.sql}: nora admin of the tenant, mario
 * admin of the project, luisa developer, paola viewer, ugo of another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class TeamApiTest {

    private static final String ORIGIN = "http://localhost:8090";

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    private final SessionAuthentication mario = session("mario", "ALPHA",
            List.of("odt.project.browse", "odt.user.browse", "odt.project.create"));
    private final SessionAuthentication luisa = session("luisa", "ALPHA", List.of("odt.project.browse", "odt.user.browse"));
    private final SessionAuthentication paola = session("paola", "ALPHA", List.of("odt.project.browse", "odt.user.browse"));
    private final SessionAuthentication nora = session("nora", "ALPHA",
            List.of("odt.project.browse", "odt.user.browse", "odt.project.admin"));
    private final SessionAuthentication ugo = session("ugo", "BETA", List.of("odt.project.browse", "odt.project.admin"));

    private static SessionAuthentication session(String username, String tenant, List<String> permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(username, username, username + "@example.org", tenant,
                List.of(new SessionContext.Tenant(tenant, tenant)), List.of(), permissions, now, now.plus(10, ChronoUnit.HOURS)));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, SessionAuthentication who) {
        return request.with(authentication(who)).with(csrf()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON);
    }

    private static void await(BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 100 && !done.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(done.getAsBoolean()).isTrue();
    }

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_access.team, odt_issue.issue, "
                + "odt_audit.issue_change, odt_search.issue_doc CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
    }

    private String team(String name, String lead) throws Exception {
        return JsonPath.read(mvc.perform(as(post("/api/teams"), nora).content(
                        "{\"name\":\"" + name + "\",\"lead\":" + (lead == null ? "null" : "\"" + lead + "\"") + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void theAdminManagesTeamsAndTheLeadTheirMembers() throws Exception {
        mvc.perform(as(post("/api/teams"), mario).content("{\"name\":\"Platform\"}")).andExpect(status().isForbidden());
        String platform = team("Platform", "luisa");
        mvc.perform(as(post("/api/teams"), nora).content("{\"name\":\"platform\"}")).andExpect(status().isConflict());
        mvc.perform(as(post("/api/teams"), nora).content("{\"name\":\"Web\",\"lead\":\"nobody\"}"))
                .andExpect(status().isBadRequest());
        // The lead adds and removes members; another user cannot.
        mvc.perform(as(post("/api/teams/" + platform + "/members"), luisa).content("{\"username\":\"paola\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/teams/" + platform + "/members"), paola).content("{\"username\":\"mario\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/teams/" + platform + "/members"), nora).content("{\"username\":\"paola\"}"))
                .andExpect(status().isConflict());
        mvc.perform(as(get("/api/teams/" + platform + "/members"), paola))
                .andExpect(jsonPath("$[0].username").value("paola"))
                .andExpect(jsonPath("$[0]._perm.remove").value(false));
        mvc.perform(as(get("/api/teams?q=plat"), paola))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lead").value("luisa"))
                .andExpect(jsonPath("$[0].members").value(1))
                .andExpect(jsonPath("$[0]._perm.edit").value(false))
                .andExpect(jsonPath("$[0]._perm.members").value(false));
        mvc.perform(as(get("/api/teams/" + platform), luisa)).andExpect(jsonPath("$._perm.members").value(true));
        mvc.perform(as(delete("/api/teams/" + platform + "/members/paola"), luisa)).andExpect(status().isNoContent());
        // Renamed by the admin, with the version read; another tenant does not see it.
        mvc.perform(as(put("/api/teams/" + platform), nora).content("{\"name\":\"Core\",\"lead\":\"paola\",\"version\":1}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(put("/api/teams/" + platform), nora).content("{\"name\":\"Core\",\"lead\":\"paola\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Core"));
        mvc.perform(as(get("/api/teams/" + platform), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/teams"), ugo)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anIssueNamesATeamWhichODQLFindsAndWhoseDeletionClearsIt() throws Exception {
        String platform = team("Platform", null);
        team("Mobile", null);
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("""
                        {"summary":"Login fails","type":"bug","team":"platform"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.team").value("Platform"));
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("""
                        {"summary":"Logo","type":"task","team":"Design"}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Logo\",\"type\":\"task\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.team").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(as(patch("/api/issues/ODT-2"), luisa).content("{\"team\":\"Mobile\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team").value("Mobile"));
        await(() -> jdbc.queryForObject("SELECT count(*) FROM odt_search.issue_doc WHERE team_id IS NOT NULL",
                Integer.class) == 2);
        assertThat(keys("team = Platform")).containsExactly("ODT-1");
        assertThat(keys("team IN (platform, mobile) ORDER BY key")).containsExactly("ODT-1", "ODT-2");
        mvc.perform(as(post("/api/search"), paola).content("{\"query\":\"team = Nobody\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/search"), paola).content("{\"query\":\"key = ODT-2\"}"))
                .andExpect(jsonPath("$.items[0].team").value("Mobile"));
        // The table groups, filters and sorts by the name of the team.
        mvc.perform(as(post("/api/search"), paola).content("{\"query\":\"\",\"groupBy\":[\"team\"]}"))
                .andExpect(jsonPath("$.groups[*].value").value(org.hamcrest.Matchers.contains("Mobile", "Platform")))
                .andExpect(jsonPath("$.groups[*].count").value(org.hamcrest.Matchers.contains(1, 1)));
        mvc.perform(as(post("/api/search"), paola).content("""
                        {"query":"","groupBy":["team"],"groupPath":[{"field":"team","value":"Platform"}]}"""))
                .andExpect(jsonPath("$.items[*].key").value(org.hamcrest.Matchers.contains("ODT-1")));
        mvc.perform(as(post("/api/search"), paola).content("{\"query\":\"\",\"distinct\":\"team\"}"))
                .andExpect(jsonPath("$.values").value(org.hamcrest.Matchers.contains("Mobile", "Platform")));
        mvc.perform(as(post("/api/search"), paola).content("""
                        {"query":"","filters":[{"field":"team","kind":"select","values":["Mobile"]}]}"""))
                .andExpect(jsonPath("$.items[*].key").value(org.hamcrest.Matchers.contains("ODT-2")));
        mvc.perform(as(post("/api/search"), paola).content("""
                        {"query":"","sort":{"field":"team","direction":"desc"}}"""))
                .andExpect(jsonPath("$.items[*].key").value(org.hamcrest.Matchers.contains("ODT-1", "ODT-2")));
        mvc.perform(as(get("/api/issues/ODT-2/history"), paola))
                .andExpect(jsonPath("$[?(@.field == 'team')].to").value(org.hamcrest.Matchers.hasItem("Mobile")));
        // Deleted: the issue keeps no team, with a row of history by its name; the search no longer finds it.
        mvc.perform(as(delete("/api/teams/" + platform), nora)).andExpect(status().isNoContent());
        await(() -> jdbc.queryForObject("SELECT count(*) FROM odt_search.issue_doc WHERE team_id IS NOT NULL",
                Integer.class) == 1);
        mvc.perform(as(get("/api/issues/ODT-1"), paola)).andExpect(jsonPath("$.team").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(as(get("/api/issues/ODT-1/history"), paola))
                .andExpect(jsonPath("$[?(@.field == 'team' && @.to == null)].from").value(org.hamcrest.Matchers.contains("Platform")));
        assertThat(keys("team IS EMPTY")).containsExactly("ODT-1");
    }

    private List<String> keys(String query) throws Exception {
        String body = mvc.perform(as(post("/api/search"), paola).content("{\"query\":\"" + query + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].key");
    }
}
