/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicwarez.authz.session.SessionAuthentication;
import com.logicwarez.authz.session.SessionContext;
import com.logicwarez.opendevtrack.TestInfrastructure;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAPI definition of {@code /api} is a contract kept in the repository (ADR-21, A3): this test generates it and
 * fails when it differs from {@code docs/openapi.json}, so every change to the API shows up in review. To write it again
 * after a change: {@code mvn test -Dtest=OpenApiContractTest -Dopenapi.update=true}; the generated copy is also left in
 * {@code target/openapi.json}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
class OpenApiContractTest {

    static final Path CONTRACT = Path.of("..", "docs", "openapi.json");

    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

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
    void theDefinitionIsTheOneInTheRepository() throws Exception {
        String body = mvc.perform(get("/v3/api-docs").with(authentication(anyone())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode api = JSON.readTree(body);
        // The indentation uses the line separator of the system: LF everywhere, the same file on Windows and in CI
        String generated = JSON.writeValueAsString(api).replace("\r\n", "\n") + "\n";
        Files.writeString(Path.of("target", "openapi.json"), generated, StandardCharsets.UTF_8);

        List<String> undocumented = new ArrayList<>();
        api.get("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            if (!operation.getValue().has("summary")) {
                undocumented.add(operation.getKey().toUpperCase() + " " + path.getKey());
            }
        }));
        assertThat(undocumented).as("Paths without @ApiDoc").isEmpty();
        assertThat(api.get("openapi").asString()).startsWith("3.1");

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(CONTRACT, generated, StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.readString(CONTRACT, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .as("%s differs from the code: write it again with -Dopenapi.update=true (see the javadoc)", CONTRACT)
                .isEqualTo(generated);
    }

    @Test
    void theDefinitionNeedsAUser() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    private static SessionAuthentication anyone() {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext("ugo", "ugo", "ugo@example.org", "ALPHA",
                List.of(new SessionContext.Tenant("ALPHA", "ALPHA")), List.of(), List.of(), now,
                now.plus(1, ChronoUnit.HOURS)));
    }
}
