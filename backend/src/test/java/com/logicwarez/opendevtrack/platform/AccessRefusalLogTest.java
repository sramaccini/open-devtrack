/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A log line for each refusal of access control (ASVS 7.2.2), whoever refuses it, with the user and the tenant; the
 * not-found of the API too, since another tenant gets the same; nothing for an answer that is not a refusal.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@ExtendWith(OutputCaptureExtension.class)
class AccessRefusalLogTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    MockMvc mvc;

    private final SessionAuthentication mario = session("mario", "odt.project.browse");
    private final SessionAuthentication nobody = session("nobody");

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
    }

    private static SessionAuthentication session(String user, String... permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(user, user, user + "@example.org", "ALPHA",
                List.of(new SessionContext.Tenant("ALPHA", "ALPHA")), List.of(), List.of(permissions), now,
                now.plus(1, ChronoUnit.HOURS)));
    }

    @Test
    void refusalsAreLoggedWithTheUser(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/groups").with(authentication(nobody))).andExpect(status().isForbidden());
        mvc.perform(post("/api/groups").with(authentication(mario)).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/projects/NOPE").with(authentication(mario))).andExpect(status().isNotFound());

        assertThat(output.getOut()).contains(
                "Access refused GET /api/projects: user -, tenant -",
                "Access refused GET /api/groups: user nobody, tenant ALPHA",
                "Access refused POST /api/groups: user mario, tenant ALPHA",
                "Not found GET /api/projects/NOPE: user mario, tenant ALPHA");
    }

    @Test
    void anAnswerThatIsNoRefusalIsNotLogged(CapturedOutput output) throws Exception {
        mvc.perform(get("/api/projects").with(authentication(mario))).andExpect(status().isOk());

        assertThat(output.getOut()).doesNotContain("Access refused", "Not found GET");
    }
}
