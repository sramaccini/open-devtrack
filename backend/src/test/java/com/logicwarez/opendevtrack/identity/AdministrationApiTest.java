/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.identity;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The calls of the Administration screens (ADR-22) to the admin API of authz-library, with the sessions of
 * OpenDevTrack and the tables in the schema {@code authz}: the admin of the users of ALPHA lists them and gives roles,
 * a user already known in another tenant gets roles there too, a member is refused, the global role stays with the
 * global admin, and the sessions of a user can be ended. Users in {@code project/users.sql} and
 * {@code identity/administration.sql}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql({"/project/users.sql", "/identity/administration.sql"})
class AdministrationApiTest {

    private static final String ORIGIN = "http://localhost:8090";

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    MockMvc mvc;

    private final SessionAuthentication admin = session("mario", "ALPHA",
            List.of("odt.project.browse", "authz.rbac.manage", "authz.session.revoke"));
    private final SessionAuthentication member = session("luisa", "ALPHA", List.of("odt.project.browse"));

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    private static SessionAuthentication session(String username, String tenant, List<String> permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(username, username, username + "@example.org", tenant,
                List.of(new SessionContext.Tenant(tenant, tenant)), List.of(), permissions, now, now.plus(10, ChronoUnit.HOURS)));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, SessionAuthentication who) {
        return request.with(authentication(who)).with(csrf()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void theAdminOfTheUsersListsThemAndGivesRoles() throws Exception {
        mvc.perform(as(get("/api/v1/admin/tenants/ALPHA/users"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItem("luisa")))
                .andExpect(jsonPath("$[*].username", not(hasItem("ugo"))));

        mvc.perform(as(put("/api/v1/admin/tenants/ALPHA/users/luisa/roles"), admin)
                        .content("{\"roles\":[\"ODT_MEMBER\",\"ODT_PROJECT_CREATOR\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.ALPHA.length()").value(2));

        // A new user, then one already known in another tenant: 409, and the roles are given all the same
        String nina = "nina" + System.nanoTime();
        mvc.perform(as(post("/api/v1/admin/users"), admin).content("{\"username\":\"" + nina + "\",\"email\":\"nina@example.org\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/v1/admin/users"), admin).content("{\"username\":\"vera\",\"email\":\"vera@example.org\"}"))
                .andExpect(status().isConflict());
        mvc.perform(as(put("/api/v1/admin/tenants/ALPHA/users/vera/roles"), admin).content("{\"roles\":[\"ODT_MEMBER\"]}"))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/users").param("q", "vera"), session("mario", "ALPHA", List.of("odt.user.browse"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username", hasItem("vera")));

        // The role of global admin is not the admin of a tenant's to give
        mvc.perform(as(put("/api/v1/admin/tenants/ALPHA/users/luisa/roles"), admin).content("{\"roles\":[\"GLOBAL_ADMIN\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/admin/audit"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actor").value("mario"));

        // luisa gets back the roles of project/users.sql, which the other tests expect
        mvc.perform(as(put("/api/v1/admin/tenants/ALPHA/users/luisa/roles"), admin).content("{\"roles\":[\"ODT_MEMBER\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void aMemberIsRefusedAndSessionsCanBeEnded() throws Exception {
        mvc.perform(as(get("/api/v1/admin/tenants/ALPHA/users"), member)).andExpect(status().isForbidden());
        mvc.perform(as(put("/api/v1/admin/tenants/ALPHA/users/luisa/roles"), member).content("{\"roles\":[]}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/admin/tenants/BETA/users"), admin)).andExpect(status().isForbidden());

        mvc.perform(as(delete("/bff/admin/users/luisa/sessions"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_id").value("ALPHA"));
        mvc.perform(as(delete("/bff/admin/users/luisa/sessions"), member)).andExpect(status().isForbidden());
    }
}
