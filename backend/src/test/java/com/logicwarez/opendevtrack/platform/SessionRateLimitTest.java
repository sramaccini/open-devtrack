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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The requests with a session are limited per user (ADR-24, A9, ASVS 11.1.4): past the limit, 429 with Retry-After. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@TestPropertySource(properties = "authz.bff.session-rate-limit.max-requests=3")
class SessionRateLimitTest {

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

    @Test
    void pastTheLimitTheAnswerIs429() throws Exception {
        Instant now = Instant.now();
        SessionAuthentication rita = new SessionAuthentication(new SessionContext("rita", "rita", "rita@example.org",
                "ALPHA", List.of(new SessionContext.Tenant("ALPHA", "ALPHA")), List.of(), List.of("odt.project.browse"),
                now, now.plus(1, ChronoUnit.HOURS)));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/projects").with(authentication(rita))).andExpect(status().isOk());
        }
        mvc.perform(get("/api/projects").with(authentication(rita)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
