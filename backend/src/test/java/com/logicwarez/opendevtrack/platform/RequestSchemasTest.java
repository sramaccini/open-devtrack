/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The JSON bodies of {@code /api} are checked against the schemas of the OpenAPI definition (ASVS 13.2.2): a field of
 * the wrong type, here or in a schema it refers to, is a 400 with its path; a {@code null} and a field the schema does
 * not name pass, as the forms of the screens send them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
class RequestSchemasTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    MockMvc mvc;

    private final SessionAuthentication mario = session("mario", "odt.project.browse");

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
    void aBodyThatDoesNotMatchIsRefusedWithItsFields() throws Exception {
        mvc.perform(post("/api/search").with(authentication(mario)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"\", \"page\": \"two\", \"groupBy\": [3], \"sort\": \"key\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("The body does not match the API definition"))
                .andExpect(jsonPath("$.errors[0].path").value("/groupBy/0"))
                .andExpect(jsonPath("$.errors[0].code").value("type"))
                .andExpect(jsonPath("$.errors[1].path").value("/page"))
                .andExpect(jsonPath("$.errors[2].path").value("/sort"));
    }

    @Test
    void nullsPass() throws Exception {
        mvc.perform(post("/api/search").with(authentication(mario)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"\", \"page\": null, \"sort\": null}"))
                .andExpect(status().isOk());
    }

    @Test
    void aFieldTheSchemaDoesNotNameIsRefused() throws Exception {
        mvc.perform(post("/api/search").with(authentication(mario)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"\", \"_perm\": {\"edit\": true}, \"filters\": [{\"field\": \"type\","
                                + " \"values\": [\"BUG\"], \"key\": \"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("/_perm"))
                .andExpect(jsonPath("$.errors[0].code").value("additionalProperties"))
                .andExpect(jsonPath("$.errors[1].path").value("/filters/0/key"))
                .andExpect(jsonPath("$.errors[1].code").value("additionalProperties"));
    }
}
