/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.project;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The project roles in {@code POST /authz/check} of authz-library (A1, {@link ProjectPermissions}): mario creates WEB
 * and is its admin, luisa is a developer, paola is not a member, ugo is in another tenant, nora is admin of the tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class ProjectPermissionsTest {

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

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member CASCADE");
        SessionAuthentication mario = session("mario", "ALPHA", List.of("odt.project.browse", "odt.user.browse",
                "odt.project.create"));
        mvc.perform(post("/api/projects").with(authentication(mario)).with(csrf()).header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"WEB\",\"name\":\"Web\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/projects/WEB/members").with(authentication(mario)).with(csrf()).header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
    }

    private static SessionAuthentication session(String username, String tenant, List<String> permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(username, username, username + "@example.org", tenant,
                List.of(new SessionContext.Tenant(tenant, tenant)), List.of(), permissions, now, now.plus(10, ChronoUnit.HOURS)));
    }

    private ResultActions check(SessionAuthentication who, String checks) throws Exception {
        return mvc.perform(post("/authz/check").with(authentication(who)).with(csrf()).header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"checks\":[" + checks + "]}"))
                .andExpect(status().isOk());
    }

    private static String item(String permission, String type, String id) {
        return "{\"permission\":\"" + permission + "\",\"resource\":{\"type\":\"" + type + "\",\"id\":\"" + id + "\"}}";
    }

    @Test
    void theRoleInTheProjectDecides() throws Exception {
        String checks = String.join(",", item("odt.issue.edit", "project", "WEB"), item("odt.project.edit", "project", "WEB"),
                item("odt.issue.delete", "issue", "WEB-12"), item("odt.issue.browse", "project", "NONE"));
        check(session("luisa", "ALPHA", MEMBER), checks)
                .andExpect(jsonPath("$.results[0].allowed").value(true))
                .andExpect(jsonPath("$.results[1].allowed").value(false))
                .andExpect(jsonPath("$.results[2].allowed").value(false))
                .andExpect(jsonPath("$.results[3].allowed").value(false));
        check(session("mario", "ALPHA", MEMBER), checks)
                .andExpect(jsonPath("$.results[1].allowed").value(true))
                .andExpect(jsonPath("$.results[2].allowed").value(true));
        check(session("paola", "ALPHA", MEMBER), checks)
                .andExpect(jsonPath("$.results[0].allowed").value(false));
    }

    @Test
    void anotherTenantSeesNothingAndTheAdminOfTheTenantEverything() throws Exception {
        check(session("ugo", "BETA", MEMBER), item("odt.issue.browse", "project", "WEB"))
                .andExpect(jsonPath("$.results[0].allowed").value(false));
        check(session("nora", "ALPHA", List.of("odt.project.browse", "odt.project.admin")),
                item("odt.member.manage", "project", "WEB"))
                .andExpect(jsonPath("$.results[0].allowed").value(true));
    }

    @Test
    void theOtherChecksKeepThePermissionsOfTheSession() throws Exception {
        check(session("paola", "ALPHA", MEMBER), String.join(",", item("odt.project.create", "project", "WEB"),
                item("odt.user.browse", "project", "WEB"), "{\"permission\":\"odt.user.browse\"}"))
                .andExpect(jsonPath("$.results[0].allowed").value(false))
                .andExpect(jsonPath("$.results[1].allowed").value(true))
                .andExpect(jsonPath("$.results[2].allowed").value(true));
        mvc.perform(get("/authz/capabilities").param("app", "opendevtrack").with(authentication(session("luisa", "ALPHA", MEMBER))).header("Origin", ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.length()").value(2));
    }
}
