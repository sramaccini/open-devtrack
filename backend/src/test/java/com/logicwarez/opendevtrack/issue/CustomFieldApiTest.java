/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Custom fields of the issues (ADR-11). Users of {@code project/users.sql}: mario admin of ODT, luisa developer,
 * paola viewer, ugo of another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class CustomFieldApiTest {
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

    MockMvc mvc;

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
    private final SessionAuthentication paola = session("paola", "ALPHA", MEMBER);
    private final SessionAuthentication ugo = session("ugo", "BETA", CREATOR);

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_issue.field_definition, "
                + "odt_audit.issue_change CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"OpenDevTrack\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
        define("{\"key\":\"customer\",\"label\":\"Customer\",\"type\":\"text\",\"requiredIf\":\"issue.type == 'bug'\"}", 201);
        define("{\"key\":\"severity\",\"label\":\"Severity\",\"type\":\"select\",\"options\":\"low: Low\\nhigh: High\"}", 201);
        define("{\"key\":\"reviewer\",\"label\":\"Reviewer\",\"type\":\"user\"}", 201);
        define("{\"key\":\"points\",\"label\":\"Points\",\"type\":\"number\"}", 201);
        define("{\"key\":\"due\",\"label\":\"Due\",\"type\":\"date\"}", 201);
        define("{\"key\":\"platforms\",\"label\":\"Platforms\",\"type\":\"multiselect\",\"options\":\"web\\nandroid\\nios: iOS\"}",
                201);
    }

    private void define(String body, int expected) throws Exception {
        mvc.perform(as(post("/api/projects/ODT/field-definitions"), mario).content(body)).andExpect(status().is(expected));
    }

    private void issue(String body, int expected) throws Exception {
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content(body)).andExpect(status().is(expected));
    }

    @Test
    void theAdminsDefineTheFields() throws Exception {
        mvc.perform(as(post("/api/projects/ODT/field-definitions"), luisa)
                        .content("{\"key\":\"team\",\"label\":\"Team\",\"type\":\"text\"}"))
                .andExpect(status().isForbidden());
        define("{\"key\":\"customer\",\"label\":\"Customer\",\"type\":\"text\"}", 409);
        define("{\"key\":\"Team\",\"label\":\"Team\",\"type\":\"text\"}", 400);
        define("{\"key\":\"team\",\"label\":\"Team\",\"type\":\"colour\"}", 400);
        define("{\"key\":\"team\",\"label\":\"Team\",\"type\":\"select\"}", 400);
        define("{\"key\":\"team\",\"label\":\"Team\",\"type\":\"text\",\"requiredIf\":\"issue.type ==\"}", 400);
        mvc.perform(as(get("/api/projects/ODT/field-definitions"), paola))
                .andExpect(jsonPath("$[*].key").value(contains("customer", "severity", "reviewer", "points", "due", "platforms")))
                .andExpect(jsonPath("$[1].options").value("low: Low\nhigh: High"))
                .andExpect(jsonPath("$[0].requiredIf").value("issue.type == 'bug'"))
                .andExpect(jsonPath("$[0]._perm.edit").value(false));
        // The rows of fieldsFrom for a new issue: only the fields always required are marked.
        mvc.perform(as(get("/api/projects/ODT/fields"), paola))
                .andExpect(jsonPath("$[0].key").value("customer"))
                .andExpect(jsonPath("$[0].required").value(false))
                .andExpect(jsonPath("$[1].type").value("select"))
                .andExpect(jsonPath("$[1].options[1].value").value("high"))
                .andExpect(jsonPath("$[1].options[1].label").value("High"))
                .andExpect(jsonPath("$[5].options[2].label").value("iOS"));
        mvc.perform(as(get("/api/projects/ODT/fields"), ugo)).andExpect(status().isNotFound());
    }

    @Test
    void theValuesAreCheckedAgainstTheDefinitions() throws Exception {
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("""
                        {"summary":"Login fails","type":"bug","custom":{"customer":"ACME","severity":"mid","reviewer":"ugo",
                         "points":"three","due":"tomorrow","platforms":["web","tv"],"team":"core"}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].path").value(contains("/custom/severity", "/custom/reviewer",
                        "/custom/points", "/custom/due", "/custom/platforms", "/custom/team")))
                .andExpect(jsonPath("$.errors[0].code").value("customFieldInvalid"))
                .andExpect(jsonPath("$.errors[5].code").value("customFieldUnknown"));
        // A required field missing: the error is on it, with the code of the screens.
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("""
                        {"summary":"Login fails","type":"bug","custom":{"severity":"high"}}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].path").value("/custom/customer"))
                .andExpect(jsonPath("$.errors[0].code").value("customFieldRequired"));
        // Nothing was saved, not even a number of the project.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odt_issue.issue", Integer.class)).isZero();
        issue("""
                {"summary":"Login fails","type":"bug","custom":{"customer":" ACME ","severity":"high","reviewer":"luisa",
                 "points":"3.50","due":"2026-11-30","platforms":["web","ios","web"]}}""", 201);
        mvc.perform(as(get("/api/issues/ODT-1"), paola))
                .andExpect(jsonPath("$.custom.customer").value("ACME"))
                .andExpect(jsonPath("$.custom.points").value(3.5))
                .andExpect(jsonPath("$.custom.due").value("2026-11-30"))
                .andExpect(jsonPath("$.custom.platforms").value(contains("web", "ios")));
        // For the issue as it is the conditional field is required.
        mvc.perform(as(get("/api/issues/ODT-1/fields"), paola))
                .andExpect(jsonPath("$[0].required").value(true))
                .andExpect(jsonPath("$[1].required").value(false));
    }

    @Test
    void changesAreInTheChangelogAndArchivedFieldsKeepTheirValues() throws Exception {
        issue("""
                {"summary":"Export","custom":{"severity":"low","points":2}}""", 201);
        mvc.perform(as(put("/api/issues/ODT-1"), luisa).content("""
                        {"summary":"Export","custom":{"severity":"high","points":2},"version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom.severity").value("high"));
        String changes = "SELECT count(*) FROM odt_audit.issue_change WHERE field = 'custom.severity'";
        for (int i = 0; i < 100 && jdbc.queryForObject(changes, Integer.class) < 2; i++) {
            Thread.sleep(50);
        }
        assertThat(jdbc.queryForList("SELECT old_value, new_value FROM odt_audit.issue_change "
                + "WHERE field = 'custom.severity' ORDER BY changed_at"))
                .extracting(r -> r.get("old_value") + " > " + r.get("new_value"))
                .containsExactly("null > low", "low > high");
        // A request without custom keeps the values, as one from an older screen.
        mvc.perform(as(put("/api/issues/ODT-1"), luisa).content("{\"summary\":\"Export CSV\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom.points").value(2));
        // Archived: out of the forms, its value kept whatever the form sends.
        String definitions = mvc.perform(as(get("/api/projects/ODT/field-definitions"), mario))
                .andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(definitions, "$[1].version");
        mvc.perform(as(put("/api/projects/ODT/field-definitions/severity"), mario).content(
                        "{\"label\":\"Severity\",\"options\":\"low\\nhigh\",\"archived\":true,\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        mvc.perform(as(get("/api/issues/ODT-1/fields"), luisa))
                .andExpect(jsonPath("$[*].key").value(contains("customer", "reviewer", "points", "due", "platforms")));
        mvc.perform(as(put("/api/issues/ODT-1"), luisa).content("""
                        {"summary":"Export CSV","custom":{"severity":"low"},"version":2}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom.severity").value("high"))
                .andExpect(jsonPath("$.custom.points").doesNotExist());
    }
}
