/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * Permission schemes (ADR-22): the default one has the sets of the project roles; a scheme that takes the creation of
 * issues from the developers takes it from {@code _perm}, from the API (403) and from {@code /authz/check}; the rules
 * that keep a project usable; a scheme in use, or the default one, is not removed; only the admin of the tenant changes
 * them. Users in {@code project/users.sql}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class PermissionSchemeApiTest {

    private static final String ORIGIN = "http://localhost:8090";
    private static final String SCHEMES = "/api/permission-schemes";
    private static final String ALL_BUT_ADMIN = "\"odt.issue.browse\",\"odt.issue.create\",\"odt.issue.edit\","
            + "\"odt.issue.transition\",\"odt.issue.comment\",\"odt.issue.attach\",\"odt.sprint.manage\"";

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    private final SessionAuthentication mario = session("mario", "ALPHA",
            List.of("odt.project.browse", "odt.user.browse", "odt.project.create"));
    private final SessionAuthentication luisa = session("luisa", "ALPHA", List.of("odt.project.browse", "odt.user.browse"));
    private final SessionAuthentication nora = session("nora", "ALPHA", List.of("odt.project.browse", "odt.project.admin"));

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_access.project_scheme,"
                + " odt_access.permission_scheme CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"WEB\",\"name\":\"Web\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/WEB/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
    }

    private static SessionAuthentication session(String username, String tenant, List<String> permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(username, username, username + "@example.org", tenant,
                List.of(new SessionContext.Tenant(tenant, tenant)), List.of(), permissions, now, now.plus(10, ChronoUnit.HOURS)));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, SessionAuthentication who) {
        return request.with(authentication(who)).with(csrf()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON);
    }

    private String add(String name) throws Exception {
        String body = mvc.perform(as(post(SCHEMES), nora).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static String scheme(String name, String viewer, String developer, String admin, int version) {
        return "{\"name\":\"" + name + "\",\"viewer\":[" + viewer + "],\"developer\":[" + developer + "],\"admin\":["
                + admin + "],\"version\":" + version + "}";
    }

    @Test
    void aSchemeDecidesWhatARoleGives() throws Exception {
        mvc.perform(as(get(SCHEMES), luisa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].default").value(true))
                .andExpect(jsonPath("$[0].developer.length()").value(7))
                .andExpect(jsonPath("$[0]._perm.edit").value(false));
        mvc.perform(as(get("/api/projects/WEB"), luisa))
                .andExpect(jsonPath("$.permissionSchemeName").value("Default"))
                .andExpect(jsonPath("$._perm.createIssue").value(true));

        // Developers who do not create issues
        String strict = add("Strict");
        mvc.perform(as(put(SCHEMES + "/" + strict), nora).content(scheme("Strict", "\"odt.issue.browse\"",
                        "\"odt.issue.browse\",\"odt.issue.edit\",\"odt.issue.comment\"",
                        ALL_BUT_ADMIN + ",\"odt.issue.delete\",\"odt.project.edit\",\"odt.member.manage\"", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.developer.length()").value(3));
        mvc.perform(as(put("/api/projects/WEB/permission-scheme"), luisa).content("{\"scheme\":\"" + strict + "\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(put("/api/projects/WEB/permission-scheme"), mario).content("{\"scheme\":\"" + strict + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permissionSchemeName").value("Strict"));

        mvc.perform(as(get("/api/projects/WEB"), luisa)).andExpect(jsonPath("$._perm.createIssue").value(false));
        mvc.perform(as(post("/api/projects/WEB/issues"), luisa).content("{\"summary\":\"Not allowed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/authz/check"), luisa).content("{\"checks\":["
                        + "{\"permission\":\"odt.issue.create\",\"resource\":{\"type\":\"project\",\"id\":\"WEB\"}},"
                        + "{\"permission\":\"odt.issue.edit\",\"resource\":{\"type\":\"project\",\"id\":\"WEB\"}}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].allowed").value(false))
                .andExpect(jsonPath("$.results[1].allowed").value(true));

        // In use: not removed; back to the default, it is
        mvc.perform(as(delete(SCHEMES + "/" + strict), nora)).andExpect(status().isConflict());
        String fallback = JsonPath.read(mvc.perform(as(get(SCHEMES), nora)).andReturn().getResponse().getContentAsString(),
                "$[0].id");
        mvc.perform(as(delete(SCHEMES + "/" + fallback), nora)).andExpect(status().isConflict());
        mvc.perform(as(put("/api/projects/WEB/permission-scheme"), mario).content("{\"scheme\":\"" + fallback + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/projects/WEB"), luisa)).andExpect(jsonPath("$._perm.createIssue").value(true));
        mvc.perform(as(delete(SCHEMES + "/" + strict), nora)).andExpect(status().isNoContent());
    }

    @Test
    void theRulesKeepAProjectUsable() throws Exception {
        String id = add("Loose");
        String admin = "\"odt.issue.browse\",\"odt.project.edit\",\"odt.member.manage\"";
        mvc.perform(as(put(SCHEMES + "/" + id), nora).content(scheme("Loose", "", "\"odt.issue.browse\"", admin, 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(as(put(SCHEMES + "/" + id), nora).content(scheme("Loose", "\"odt.issue.browse\"", "\"odt.issue.browse\"",
                        "\"odt.issue.browse\",\"odt.project.edit\"", 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(as(put(SCHEMES + "/" + id), nora).content(scheme("Loose", "\"odt.issue.browse\",\"odt.project.create\"",
                        "\"odt.issue.browse\"", admin, 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(as(put(SCHEMES + "/" + id), nora).content(scheme("Loose", "\"odt.issue.browse\"", "\"odt.issue.browse\"",
                        admin, 0)))
                .andExpect(status().isOk());
        mvc.perform(as(put(SCHEMES + "/" + id), nora).content(scheme("Loose", "\"odt.issue.browse\"", "\"odt.issue.browse\"",
                        admin, 0)))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(post(SCHEMES), nora).content("{\"name\":\"loose\"}")).andExpect(status().isConflict());

        // Only the admin of the tenant changes them
        mvc.perform(as(post(SCHEMES), mario).content("{\"name\":\"Mine\"}")).andExpect(status().isForbidden());
        mvc.perform(as(delete(SCHEMES + "/" + id), mario)).andExpect(status().isForbidden());
        mvc.perform(as(get(SCHEMES + "/" + id), session("ugo", "BETA", List.of("odt.project.browse"))))
                .andExpect(status().isNotFound());
    }
}
