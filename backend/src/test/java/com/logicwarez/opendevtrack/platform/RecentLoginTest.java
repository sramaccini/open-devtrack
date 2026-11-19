/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A login younger than 15 minutes before the sensitive actions of a session (ADR-24 point 4, O9, A8; ASVS 3.7.1,
 * 4.3.3): an older one gets 401 recent-login-required with maxAge, which the Quadrava runtime turns into a new login.
 * The test profile turns the paths off for the other tests; here some of those of application.yml, as written there.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@TestPropertySource(properties = {
        "authz.bff.recent-login.paths[0]=POST /api/admin/**",
        "authz.bff.recent-login.paths[1]=POST /api/groups/**",
        "authz.bff.recent-login.paths[2]=POST /api/projects/*/webhooks/**",
        "authz.bff.recent-login.paths[3]=GET /api/admin/users/*/export"})
class RecentLoginTest {

    /** Where authz-library keeps the time of the login in the session (SessionGuards.AUTH_TIME). */
    private static final String AUTH_TIME = "authz.bff.auth_time";

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, Instant loggedInAt) {
        Instant now = Instant.now();
        SessionAuthentication admin = new SessionAuthentication(new SessionContext("mario", "mario",
                "mario@example.org", "ALPHA", List.of(new SessionContext.Tenant("ALPHA", "ALPHA")), List.of(),
                List.of("odt.project.browse", "authz.rbac.manage"), now, now.plus(10, ChronoUnit.HOURS)));
        MockHttpSession session = new MockHttpSession();
        if (loggedInAt != null) {
            session.setAttribute(AUTH_TIME, loggedInAt);
        }
        return request.session(session).with(authentication(admin)).with(csrf())
                .header("Origin", "http://localhost:8090").contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void anOldLoginIsAskedAgainOnlyOnTheMarkedPaths() throws Exception {
        Instant old = Instant.now().minus(20, ChronoUnit.MINUTES);
        for (MockHttpServletRequestBuilder request : List.of(
                post("/api/admin/users/luisa/remove").content("{\"confirm\":\"luisa\"}"),
                post("/api/groups").content("{\"name\":\"Ops\"}"),
                post("/api/projects/ODT/webhooks").content("{}"),
                get("/api/admin/users/luisa/export"))) {
            mvc.perform(as(request, old))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("urn:problem:authz:recent-login-required"))
                    .andExpect(jsonPath("$.maxAge").value(900));
        }
        // A session without the time of the login counts as old
        mvc.perform(as(post("/api/groups").content("{\"name\":\"Ops\"}"), null)).andExpect(status().isUnauthorized());
        // Reading is not marked
        mvc.perform(as(get("/api/groups"), old)).andExpect(status().isOk());
    }

    @Test
    void aRecentLoginGoesThrough() throws Exception {
        int status = mvc.perform(as(post("/api/groups").content("{\"name\":\"\"}"), Instant.now().minus(1, ChronoUnit.MINUTES)))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401);
        int export = mvc.perform(as(get("/api/admin/users/luisa/export"), Instant.now().minus(1, ChronoUnit.MINUTES)))
                .andReturn().getResponse().getStatus();
        assertThat(export).isNotEqualTo(401);
    }
}
