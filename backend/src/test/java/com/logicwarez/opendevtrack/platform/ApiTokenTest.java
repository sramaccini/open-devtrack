/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicwarez.opendevtrack.TestInfrastructure;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The API with a Keycloak access token instead of the session (ADR-21, A4; the bearer access of authz-library): the
 * permissions of the user, the tenant of {@code X-Tenant}, a service account as a member of a project, no CSRF token,
 * and the refusals (no valid token, a tenant not the user's, a cookie with the token). Users in
 * {@code platform/tokens.sql}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/platform/tokens.sql")
class ApiTokenTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    @Autowired
    JdbcTemplate jdbc;

    /** The token is the user name: what is under test is what the API does with the user, not the signature. */
    @MockitoBean(name = "authzBearerJwtDecoder")
    JwtDecoder tokens;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue CASCADE");
        given(tokens.decode(anyString())).willAnswer(call -> {
            String name = call.getArgument(0);
            if (name.equals("forged")) {
                throw new BadJwtException("Bad signature");
            }
            return Jwt.withTokenValue(name).header("alg", "none").subject(name).claim("preferred_username", name)
                    .claim("azp", name.startsWith("service-account-") ? "ci" : "opendevtrack-tools").build();
        });
    }

    /** A request with the token of a user: no session, no CSRF token, no Origin. */
    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String user) {
        return request.header("Authorization", "Bearer " + user).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void aTokenWorksAsTheUser() throws Exception {
        mvc.perform(as(post("/api/projects"), "mario").content("{\"key\":\"WEB\",\"name\":\"Web\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._perm.edit").value(true));
        mvc.perform(as(post("/api/projects/WEB/issues"), "mario").content("{\"type\":\"task\",\"summary\":\"First\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("WEB-1"))
                .andExpect(jsonPath("$.reporter").value("mario"));
        mvc.perform(as(put("/api/issues/WEB-1"), "mario")
                        .content("{\"type\":\"task\",\"summary\":\"Changed\",\"priority\":\"medium\",\"version\":99}"))
                .andExpect(status().isPreconditionFailed());
        // Global permissions from the RBAC roles: luisa may not create projects, nor see the issues of WEB
        mvc.perform(as(post("/api/projects"), "luisa").content("{\"key\":\"APP\",\"name\":\"App\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/projects/WEB/issues"), "luisa")).andExpect(status().isForbidden());
    }

    @Test
    void theTenantHeaderPicksTheTenant() throws Exception {
        mvc.perform(as(post("/api/projects"), "mario").content("{\"key\":\"WEB\",\"name\":\"Web\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(get("/api/projects"), "nora").header("X-Tenant", "ALPHA"))
                .andExpect(jsonPath("$[0].key").value("WEB"));
        mvc.perform(as(get("/api/projects"), "nora").header("X-Tenant", "BETA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/projects"), "mario").header("X-Tenant", "BETA")).andExpect(status().isForbidden());
    }

    @Test
    void aServiceAccountIsAMemberAsAnyUser() throws Exception {
        mvc.perform(as(post("/api/projects"), "mario").content("{\"key\":\"WEB\",\"name\":\"Web\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/WEB/issues"), "service-account-ci").content("{\"type\":\"bug\",\"summary\":\"Build broken\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/projects/WEB/members"), "mario")
                        .content("{\"username\":\"service-account-ci\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/WEB/issues"), "service-account-ci").content("{\"type\":\"bug\",\"summary\":\"Build broken\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reporter").value("service-account-ci"));
    }

    @Test
    void withoutAValidTokenNothingIsDone() throws Exception {
        mvc.perform(as(get("/api/projects"), "forged")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        // A user the RBAC does not know
        mvc.perform(as(get("/api/projects"), "stranger")).andExpect(status().isForbidden());
        // The session cookie and a token in the same request
        mvc.perform(as(get("/api/projects"), "mario").with(request -> {
            request.setRequestedSessionId("a-session");
            return request;
        })).andExpect(status().isBadRequest());
    }

    @Test
    void theDefinitionIsReadWithAToken() throws Exception {
        mvc.perform(as(get("/v3/api-docs"), "luisa"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("OpenDevTrack API"));
    }
}
