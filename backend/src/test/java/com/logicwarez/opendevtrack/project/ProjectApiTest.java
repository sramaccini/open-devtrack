/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Projects and members over HTTP with the sessions of authz-library (users in {@code project/users.sql}): global
 * permissions from the session, project roles from the module access, and the isolation of the tenants (ADR-09).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class ProjectApiTest {

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
    Projects projects;

    @Autowired
    TransactionTemplate transactions;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member");
    }

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
    private final SessionAuthentication ugo = session("ugo", "BETA", CREATOR);

    private void createOdt() throws Exception {
        mvc.perform(as(post("/api/projects"), mario).content("""
                        {"key":"ODT","name":"OpenDevTrack","description":"The tracker itself"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("ODT"))
                .andExpect(jsonPath("$.lead").value("mario"))
                .andExpect(jsonPath("$.role").value("admin"))
                .andExpect(jsonPath("$._perm.edit").value(true))
                .andExpect(jsonPath("$._perm.manageMembers").value(true));
    }

    @Test
    void theCreatorBecomesLeadAndAdmin() throws Exception {
        createOdt();
        mvc.perform(as(get("/api/projects"), mario))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("admin"));
        // A member of the tenant sees the project, without rights on it.
        mvc.perform(as(get("/api/projects/ODT"), luisa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$._perm.edit").value(false));
        mvc.perform(as(get("/api/projects/ODT/members"), luisa))
                .andExpect(jsonPath("$[0].username").value("mario"))
                .andExpect(jsonPath("$[0].role").value("admin"))
                .andExpect(jsonPath("$[0]._perm.remove").value(false));
    }

    @Test
    void anotherTenantDoesNotSeeTheProject() throws Exception {
        createOdt();
        mvc.perform(as(get("/api/projects"), ugo)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/projects/ODT"), ugo)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/projects/ODT/members"), ugo)).andExpect(status().isNotFound());
        // The key is unique per tenant: BETA can have its own ODT.
        mvc.perform(as(post("/api/projects"), ugo).content("{\"key\":\"ODT\",\"name\":\"Beta tracker\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(get("/api/projects/ODT"), mario)).andExpect(jsonPath("$.name").value("OpenDevTrack"));
    }

    @Test
    void creatingNeedsThePermissionAndAValidKey() throws Exception {
        mvc.perform(as(post("/api/projects"), luisa).content("{\"key\":\"WEB\",\"name\":\"Web\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"web\",\"name\":\"Web\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"WEB\",\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
        createOdt();
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"Again\"}"))
                .andExpect(status().isConflict());
        // Without odt.project.browse nothing is listed.
        mvc.perform(as(get("/api/projects"), session("paola", "ALPHA", List.of()))).andExpect(status().isForbidden());
    }

    @Test
    void onlyAnAdminEditsAndAnOldVersionIsRefused() throws Exception {
        createOdt();
        mvc.perform(as(put("/api/projects/ODT"), luisa).content("{\"name\":\"Mine\",\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(put("/api/projects/ODT"), mario).content("{\"name\":\"OpenDevTrack 1.0\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("OpenDevTrack 1.0"))
                .andExpect(jsonPath("$.version").value(1));
        // Saved from what was read before the change above.
        mvc.perform(as(put("/api/projects/ODT"), mario).content("{\"name\":\"Stale\",\"version\":0}"))
                .andExpect(status().isPreconditionFailed());
        // The lead must be a member.
        mvc.perform(as(put("/api/projects/ODT"), mario).content("{\"name\":\"OpenDevTrack\",\"lead\":\"luisa\",\"version\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void membersAreUsersOfTheTenantAndAProjectKeepsAnAdmin() throws Exception {
        createOdt();
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("developer"))
                .andExpect(jsonPath("$.grantedBy").value("mario"));
        // Unknown user, user of another tenant, unknown role.
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"nobody\",\"role\":\"viewer\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"ugo\",\"role\":\"viewer\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"owner\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/projects/ODT/members").param("q", "LU"), mario))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("luisa"));
        // A developer does not manage members.
        mvc.perform(as(post("/api/projects/ODT/members"), luisa).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/projects/ODT"), luisa))
                .andExpect(jsonPath("$.role").value("developer"))
                .andExpect(jsonPath("$._perm.createIssue").value(true))
                .andExpect(jsonPath("$._perm.edit").value(false));
        // The last admin can neither leave nor become a developer.
        mvc.perform(as(delete("/api/projects/ODT/members/mario"), mario)).andExpect(status().isConflict());
        mvc.perform(as(put("/api/projects/ODT/members/mario"), mario).content("{\"role\":\"developer\"}"))
                .andExpect(status().isConflict());
        // With a second admin, yes.
        mvc.perform(as(put("/api/projects/ODT/members/luisa"), mario).content("{\"role\":\"admin\"}")).andExpect(status().isOk());
        mvc.perform(as(delete("/api/projects/ODT/members/mario"), luisa)).andExpect(status().isNoContent());
        mvc.perform(as(delete("/api/projects/ODT/members/mario"), luisa)).andExpect(status().isNotFound());
    }

    @Test
    void usersOfTheTenantForThePickers() throws Exception {
        mvc.perform(as(get("/api/users").param("q", "LU"), mario))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("luisa"));
        mvc.perform(as(get("/api/users").param("q", "ugo"), mario)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void issueNumbersFollowEachOther() throws Exception {
        createOdt();
        UUID id = jdbc.queryForObject("SELECT id FROM odt_project.project WHERE project_key = 'ODT'", UUID.class);
        long first = transactions.execute(s -> projects.nextIssueNumber(id));
        long second = transactions.execute(s -> projects.nextIssueNumber(id));
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
    }
}
