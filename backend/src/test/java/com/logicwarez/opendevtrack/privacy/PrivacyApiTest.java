/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * The export and the removal of a user's data (ADR-24, O13; ASVS 8.3.2). rosa reports an issue assigned to herself,
 * comments, saves a filter and is mentioned by luisa; mario administers the users of ALPHA. Users in
 * {@code project/users.sql} and {@code privacy/users.sql}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql({"/project/users.sql", "/privacy/users.sql"})
class PrivacyApiTest {

    private static final String ORIGIN = "http://localhost:8090";
    private static final List<String> MEMBER = List.of("odt.project.browse", "odt.user.browse");

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    private final SessionAuthentication mario = session("mario", List.of("odt.project.browse", "odt.user.browse",
            "odt.project.create", "authz.rbac.manage"));
    private final SessionAuthentication luisa = session("luisa", MEMBER);
    private final SessionAuthentication rosa = session("rosa", MEMBER);

    private static SessionAuthentication session(String username, List<String> permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(username, username, username + "@example.org", "ALPHA",
                List.of(new SessionContext.Tenant("ALPHA", "ALPHA")), List.of(), permissions, now,
                now.plus(10, ChronoUnit.HOURS)));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, SessionAuthentication who) {
        return request.with(authentication(who)).with(csrf()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_audit.issue_change, "
                + "odt_collaboration.comment, odt_notification.message, odt_search.issue_doc, odt_search.filter, "
                + "odt_reporting.issue_state CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        for (String member : List.of("luisa", "rosa")) {
            mvc.perform(as(post("/api/projects/ODT/members"), mario)
                    .content("{\"username\":\"" + member + "\",\"role\":\"developer\"}")).andExpect(status().isOk());
        }
        mvc.perform(as(post("/api/projects/ODT/issues"), rosa)
                        .content("{\"summary\":\"Login fails\",\"type\":\"bug\",\"assignee\":\"rosa\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/issues/ODT-1/comments"), rosa).content("{\"body\":\"Seen with Chrome\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/issues/ODT-1/comments"), luisa).content("{\"body\":\"@rosa which version?\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/filters"), rosa).content("{\"name\":\"Mine\",\"query\":\"assignee = rosa\"}"))
                .andExpect(status().isCreated());
        // The reporter does not vote for her own issue: rosa votes for one of luisa's.
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"Slow search\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/issues/ODT-2/votes"), rosa)).andExpect(status().isNoContent());
        TestInfrastructure.awaitListeners(jdbc);
    }

    @Test
    void theUserAndAnAdministratorExportTheUsersData() throws Exception {
        mvc.perform(as(get("/api/me/export"), rosa))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString("opendevtrack-rosa.json")))
                .andExpect(jsonPath("$.username").value("rosa"))
                .andExpect(jsonPath("$.profile.email").value("rosa@example.org"))
                .andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].reporter").value("rosa"))
                .andExpect(jsonPath("$.issues[0].createdAt", matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*Z")))
                // Her comment, not luisa's
                .andExpect(jsonPath("$.comments.length()").value(1))
                .andExpect(jsonPath("$.comments[0].body").value("Seen with Chrome"))
                .andExpect(jsonPath("$.mentions.length()").value(1))
                .andExpect(jsonPath("$.votes.length()").value(1))
                .andExpect(jsonPath("$.filters[0].name").value("Mine"))
                .andExpect(jsonPath("$.projectRoles[0].role").value("DEVELOPER"));

        mvc.perform(as(get("/api/admin/users/rosa/export"), mario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("rosa"));
        mvc.perform(as(get("/api/admin/users/rosa/export"), luisa)).andExpect(status().isForbidden());

        // Each export is in the audit log of the tenant
        TestInfrastructure.awaitListeners(jdbc);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odt_audit.admin_event WHERE action = 'user.exported' "
                + "AND target = 'rosa' AND tenant_id = 'ALPHA'", Integer.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theAdministratorRemovesTheUserAndTheNameBecomesAPseudonym() throws Exception {
        mvc.perform(as(post("/api/admin/users/rosa/remove"), luisa).content("{\"confirm\":\"rosa\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/admin/users/mario/remove"), mario).content("{\"confirm\":\"mario\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/admin/users/rosa/remove"), mario).content("{\"confirm\":\"ros\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("/confirm"));

        String answer = mvc.perform(as(post("/api/admin/users/rosa/remove"), mario).content("{\"confirm\":\"rosa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pseudonym", matchesPattern("former-user-\\d+")))
                .andReturn().getResponse().getContentAsString();
        String pseudonym = JsonPath.read(answer, "$.pseudonym");

        // The work stays, under the pseudonym
        mvc.perform(as(get("/api/issues/ODT-1"), luisa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reporter").value(pseudonym))
                .andExpect(jsonPath("$.assignee").value(pseudonym));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odt_collaboration.comment WHERE author = ?", Integer.class,
                pseudonym)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odt_audit.issue_change WHERE author = ?", Integer.class,
                pseudonym)).isPositive();
        assertThat(jdbc.queryForObject("SELECT reporter FROM odt_search.issue_doc WHERE issue_key = 'ODT-1'", String.class)).isEqualTo(pseudonym);

        // Nothing under her name is left, and she is no longer in the tenant
        for (String where : List.of(
                "odt_issue.issue WHERE 'rosa' IN (reporter, assignee, reviewer)",
                "odt_issue.watcher WHERE username = 'rosa'",
                "odt_issue.vote WHERE username = 'rosa'",
                "odt_collaboration.comment WHERE author = 'rosa'",
                "odt_collaboration.mention WHERE username = 'rosa'",
                "odt_audit.issue_change WHERE 'rosa' IN (author, old_value, new_value)",
                "odt_notification.message WHERE 'rosa' IN (recipient, actor)",
                "odt_notification.preference WHERE username = 'rosa'",
                "odt_search.issue_doc WHERE 'rosa' IN (reporter, assignee, reviewer)",
                "odt_search.filter WHERE owner = 'rosa'",
                "odt_reporting.issue_state WHERE assignee = 'rosa'",
                "odt_access.project_member WHERE 'rosa' IN (username, granted_by)",
                "odt_audit.admin_event WHERE (before::text || after::text) LIKE '%\"rosa\"%'",
                // authz-library (A10): with no role left in any tenant she is deleted, and its audit names the pseudonym
                "authz.app_user WHERE username = 'rosa'",
                "authz.rbac_audit WHERE tenant_id = 'ALPHA' AND ('rosa' IN (actor, target) OR "
                        + "(coalesce(before_state::text, '') || coalesce(after_state::text, '')) LIKE '%\"rosa\"%')")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + where, Integer.class)).as(where).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM authz.rbac_audit WHERE action = 'USER_FORGOTTEN' "
                + "AND target = ? AND tenant_id = 'ALPHA'", Integer.class, pseudonym)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM authz.rbac_audit WHERE action = 'USER_ROLES_REPLACED' "
                + "AND target = ? AND tenant_id = 'ALPHA'", Integer.class, pseudonym)).isPositive();
        mvc.perform(as(get("/api/admin/users/rosa/export"), mario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value(nullValue()))
                .andExpect(jsonPath("$.issues.length()").value(0));

        // The audit log names the pseudonym, not the user
        TestInfrastructure.awaitListeners(jdbc);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odt_audit.admin_event WHERE action = 'user.removed' "
                + "AND target = ? AND actor = 'mario'", Integer.class, pseudonym)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odt_audit.admin_event WHERE tenant_id = 'ALPHA' AND "
                + "'rosa' IN (actor, target) AND action <> 'user.exported'", Integer.class)).isZero();
    }
}
