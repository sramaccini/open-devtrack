/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Moving an issue to another project (ADR-27; users in {@code project/users.sql}): mario is the admin of ODT and APP,
 * luisa a developer of both, paola a viewer of ODT only; ugo is in another tenant. ODT has the custom fields customer
 * (text) and region (select), APP only customer.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class MoveApiTest {

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
        jdbc.execute("TRUNCATE odt_collaboration.comment, odt_audit.issue_change, odt_notification.message CASCADE");
        project(mario, "ODT", "OpenDevTrack");
        project(mario, "APP", "App");
        project(ugo, "BET", "Beta");
        member("ODT", "luisa", "developer");
        member("ODT", "paola", "viewer");
        member("APP", "luisa", "developer");
        field("ODT", "{\"key\":\"customer\",\"label\":\"Customer\",\"type\":\"text\"}");
        field("ODT", "{\"key\":\"region\",\"label\":\"Region\",\"type\":\"select\",\"options\":\"eu: Europe\\nus: America\"}");
        field("APP", "{\"key\":\"customer\",\"label\":\"Customer\",\"type\":\"text\"}");
        mvc.perform(as(post("/api/projects/APP/issues"), luisa).content("{\"summary\":\"First of APP\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("""
                        {"type":"bug","summary":"Login fails","assignee":"luisa","reviewer":"paola","labels":["auth"],
                         "custom":{"customer":"ACME","region":"eu"}}"""))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/issues/ODT-1/comments"), luisa).content("{\"body\":\"Seen on Chrome\"}"))
                .andExpect(status().isCreated());
        TestInfrastructure.awaitListeners(jdbc);
    }

    private void project(SessionAuthentication who, String key, String name) throws Exception {
        mvc.perform(as(post("/api/projects"), who).content("{\"key\":\"" + key + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());
    }

    private void member(String project, String username, String role) throws Exception {
        mvc.perform(as(post("/api/projects/" + project + "/members"), mario)
                        .content("{\"username\":\"" + username + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
    }

    private void field(String project, String body) throws Exception {
        mvc.perform(as(post("/api/projects/" + project + "/field-definitions"), mario).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void onlyWhoMayMoveInTheSourceAndCreateInTheTargetMoves() throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/move"), luisa).content("{\"project\":\"APP\",\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/issues/ODT-1/move"), mario).content("{\"project\":\"ODT\",\"version\":0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/issues/ODT-1/move"), mario).content("{\"project\":\"NOPE\",\"version\":0}"))
                .andExpect(status().isNotFound());
        // Never to another tenant: its projects do not exist for mario
        mvc.perform(as(post("/api/issues/ODT-1/move"), mario).content("{\"project\":\"BET\",\"version\":0}"))
                .andExpect(status().isNotFound());
        mvc.perform(as(post("/api/issues/ODT-1/move"), mario).content("{\"project\":\"APP\",\"version\":7,\"fields\":{\"drop\":[\"region\"]}}"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void theDialogOffersTheOtherProjectsAndTheStatusesThere() throws Exception {
        // Not ODT itself, not BET of the other tenant
        mvc.perform(as(get("/api/issues/ODT-1/move/targets"), mario)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", contains("APP")))
                .andExpect(jsonPath("$[0].name").value("App"));
        mvc.perform(as(get("/api/issues/ODT-1/move/targets?q=open"), mario)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/issues/ODT-1/move/targets"), luisa)).andExpect(status().isForbidden());
        // The status the move would choose by itself comes first: the issue is in todo
        mvc.perform(as(get("/api/issues/ODT-1/move/statuses?project=APP&type=story"), mario)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("todo"))
                .andExpect(jsonPath("$[0].name").value("To do"))
                .andExpect(jsonPath("$[*].code", hasItem("done")));
        mvc.perform(as(get("/api/issues/ODT-1/move/statuses"), mario)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // A status chosen in the dialog
        mvc.perform(as(post("/api/issues/ODT-1/move"), mario)
                        .content("{\"project\":\"APP\",\"status\":\"done\",\"version\":0,\"fields\":{\"drop\":[\"region\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.status").value("done"));
    }

    @Test
    void aDryRunTellsWhatWouldBeLostAndChangesNothing() throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/move"), mario).content("{\"project\":\"APP\",\"dryRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(nullValue()))
                .andExpect(jsonPath("$.movedFrom").value("ODT-1"))
                .andExpect(jsonPath("$.dropped", contains("region")))
                .andExpect(jsonPath("$.cleared", contains("reviewer")))
                .andExpect(jsonPath("$.lostText").value("Region, Reviewer (paola)"));
        mvc.perform(as(get("/api/issues/ODT-1"), mario)).andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("ODT-1"))
                .andExpect(jsonPath("$.reviewer").value("paola"));
    }

    @Test
    void valuesThatCannotBeKeptMustBeListed() throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/move"), mario).content("{\"project\":\"APP\",\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("/custom/region"))
                .andExpect(jsonPath("$.errors[0].code").value(IssueMover.DROPPED));
    }

    @Test
    void theIssueMovesWithANewKeyAndTheOldOneKeepsLeadingToIt() throws Exception {
        mvc.perform(as(post("/api/issues/ODT-1/move"), mario)
                        .content("{\"project\":\"APP\",\"version\":0,\"fields\":{\"drop\":[\"region\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("APP-2"))
                .andExpect(jsonPath("$.movedFrom").value("ODT-1"))
                .andExpect(jsonPath("$.issue.key").value("APP-2"))
                .andExpect(jsonPath("$.issue.project").value("APP"))
                .andExpect(jsonPath("$.issue.custom.customer").value("ACME"))
                .andExpect(jsonPath("$.issue.custom.region").doesNotExist())
                .andExpect(jsonPath("$.issue.assignee").value("luisa"))
                .andExpect(jsonPath("$.issue.reviewer").value(nullValue()))
                .andExpect(jsonPath("$.issue.labels[0]").value("auth"));
        TestInfrastructure.awaitListeners(jdbc);

        // The old key leads to the issue as it is now, also for the permission checks
        mvc.perform(as(get("/api/issues/ODT-1"), luisa)).andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("APP-2"));
        mvc.perform(as(get("/api/issues/APP-2/comments"), luisa)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].body").value("Seen on Chrome"));
        // paola does not see APP: the issue is gone for her, as any issue of a project she is not in
        mvc.perform(as(get("/api/issues/ODT-1"), paola)).andExpect(status().isForbidden());
        // The number of the source is never given again
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Next\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.key").value("ODT-2"));

        mvc.perform(as(get("/api/issues/APP-2/history"), luisa)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].field", hasItem("project")))
                .andExpect(jsonPath("$[*].field", hasItem("custom.region")));
        mvc.perform(as(post("/api/search"), luisa).content("{\"query\":\"key = ODT-1\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].key", contains("APP-2")));
        assertThat(jdbc.queryForObject("""
                SELECT p.project_key FROM odt_agile.issue_plan pl JOIN odt_project.project p ON p.id = pl.project_id
                JOIN odt_issue.issue i ON i.id = pl.issue_id WHERE i.number = 2 AND p.project_key = 'APP'""",
                String.class)).isEqualTo("APP");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM odt_reporting.issue_state s JOIN odt_project.project p ON p.id = s.project_id
                WHERE p.project_key = 'APP'""", Integer.class)).isEqualTo(2);
        // luisa watches the issue and sees the target: told of the move
        assertThat(jdbc.queryForList("SELECT kind FROM odt_notification.message WHERE recipient = 'luisa'", String.class))
                .contains("moved");
    }
}
