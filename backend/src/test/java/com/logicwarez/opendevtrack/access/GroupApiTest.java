/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.access;

import static org.hamcrest.Matchers.contains;
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
 * Groups (ADR-22): a group's role in a project and the highest-role rule, members through a group in the list of
 * members, the last admin kept through groups too (409), and who may change the groups. Users in
 * {@code project/users.sql}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class GroupApiTest {

    private static final String ORIGIN = "http://localhost:8090";

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
    private final SessionAuthentication paola = session("paola", "ALPHA", List.of("odt.project.browse", "odt.user.browse"));
    private final SessionAuthentication nora = session("nora", "ALPHA",
            List.of("odt.project.browse", "odt.user.browse", "odt.project.admin"));

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_access.user_group CASCADE");
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

    private String group(String name, String... usernames) throws Exception {
        String id = JsonPath.read(mvc.perform(as(post("/api/groups"), nora).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        for (String username : usernames) {
            mvc.perform(as(post("/api/groups/" + id + "/members"), nora).content("{\"username\":\"" + username + "\"}"))
                    .andExpect(status().isCreated());
        }
        return id;
    }

    @Test
    void theHighestRoleCounts() throws Exception {
        String leads = group("Leads", "luisa", "paola");
        mvc.perform(as(post("/api/projects/WEB/groups"), luisa).content("{\"group\":\"" + leads + "\",\"role\":\"admin\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/projects/WEB/groups"), mario).content("{\"group\":\"" + leads + "\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Leads"));

        // luisa: developer by herself, viewer through Leads; paola: viewer through Leads only
        mvc.perform(as(get("/api/projects/WEB"), luisa)).andExpect(jsonPath("$.role").value("developer"));
        mvc.perform(as(get("/api/projects/WEB"), paola))
                .andExpect(jsonPath("$.role").value("viewer"))
                .andExpect(jsonPath("$._perm.createIssue").value(false));
        mvc.perform(as(get("/api/projects/WEB/members"), mario))
                .andExpect(jsonPath("$[*].username", contains("luisa", "mario", "paola")))
                .andExpect(jsonPath("$[0].ownRole").value("developer"))
                .andExpect(jsonPath("$[0].groups[0]").value("Leads"))
                .andExpect(jsonPath("$[2].ownRole").doesNotExist())
                .andExpect(jsonPath("$[2]._perm.remove").value(false));

        // Leads becomes admin: luisa and paola are admins, as /authz/check says
        mvc.perform(as(post("/api/projects/WEB/groups"), mario).content("{\"group\":\"" + leads + "\",\"role\":\"admin\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/projects/WEB"), luisa)).andExpect(jsonPath("$.role").value("admin"));
        mvc.perform(as(post("/authz/check"), paola).content(
                        "{\"checks\":[{\"permission\":\"odt.member.manage\",\"resource\":{\"type\":\"project\",\"id\":\"WEB\"}}]}"))
                .andExpect(jsonPath("$.results[0].allowed").value(true));
        mvc.perform(as(get("/api/projects"), paola)).andExpect(jsonPath("$[0].role").value("admin"));
    }

    @Test
    void aProjectKeepsAnAdminThroughGroupsToo() throws Exception {
        String leads = group("Leads", "paola");
        mvc.perform(as(post("/api/projects/WEB/groups"), mario).content("{\"group\":\"" + leads + "\",\"role\":\"admin\"}"))
                .andExpect(status().isOk());
        // mario may step down: paola stays admin through Leads
        mvc.perform(as(put("/api/projects/WEB/members/mario"), mario).content("{\"role\":\"developer\"}"))
                .andExpect(status().isOk());

        // Now Leads holds the only admin
        mvc.perform(as(delete("/api/groups/" + leads + "/members/paola"), nora)).andExpect(status().isConflict());
        mvc.perform(as(delete("/api/projects/WEB/groups/" + leads), paola)).andExpect(status().isConflict());
        mvc.perform(as(post("/api/projects/WEB/groups"), paola).content("{\"group\":\"" + leads + "\",\"role\":\"developer\"}"))
                .andExpect(status().isConflict());
        mvc.perform(as(delete("/api/groups/" + leads), nora)).andExpect(status().isConflict());

        mvc.perform(as(put("/api/projects/WEB/members/mario"), paola).content("{\"role\":\"admin\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(delete("/api/groups/" + leads), nora)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/projects/WEB/groups"), mario)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/projects/WEB"), paola)).andExpect(jsonPath("$.role").doesNotExist());
    }

    @Test
    void onlyTheAdminOfTheTenantChangesTheGroups() throws Exception {
        mvc.perform(as(post("/api/groups"), mario).content("{\"name\":\"Mine\"}")).andExpect(status().isForbidden());
        String leads = group("Leads");
        mvc.perform(as(post("/api/groups"), nora).content("{\"name\":\"leads\"}")).andExpect(status().isConflict());
        mvc.perform(as(post("/api/groups/" + leads + "/members"), nora).content("{\"username\":\"ugo\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/groups/" + leads + "/members"), mario).content("{\"username\":\"paola\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/groups"), luisa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]._perm.edit").value(false));
        mvc.perform(as(get("/api/groups/" + leads), session("ugo", "BETA", List.of("odt.project.browse"))))
                .andExpect(status().isNotFound());
        mvc.perform(as(post("/api/projects/WEB/groups"), mario).content("{\"group\":\"" + leads + "\",\"role\":\"owner\"}"))
                .andExpect(status().isBadRequest());
    }
}
