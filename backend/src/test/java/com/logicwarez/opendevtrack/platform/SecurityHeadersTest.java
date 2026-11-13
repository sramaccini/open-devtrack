/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The security headers of the answers of the back end (ADR-23): signed in or not, an answer or an error. HSTS only
 * over TLS: the proxy in front says so with X-Forwarded-Proto.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
class SecurityHeadersTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    MockMvc mvc;

    private final SessionAuthentication mario = session();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    private static SessionAuthentication session() {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext("mario", "mario", "mario@example.org", "ALPHA",
                List.of(new SessionContext.Tenant("ALPHA", "ALPHA")), List.of(), List.of("odt.project.browse"), now,
                now.plus(1, ChronoUnit.HOURS)));
    }

    private static void secure(ResultActions result) throws Exception {
        result.andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
    }

    @Test
    void everyAnswerHasThem() throws Exception {
        secure(mvc.perform(get("/api/projects").with(authentication(mario))).andExpect(status().isOk()));
        secure(mvc.perform(get("/api/projects/NOPE").with(authentication(mario))).andExpect(status().isNotFound()));
        secure(mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized()));
        secure(mvc.perform(get("/actuator/health")));
    }

    @Test
    void hstsOnlyOverTls() throws Exception {
        MockHttpServletRequestBuilder overTls = get("/api/projects").with(authentication(mario))
                .header("X-Forwarded-Proto", "https").secure(true);
        mvc.perform(overTls)
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
        mvc.perform(get("/api/projects").with(authentication(mario)))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
