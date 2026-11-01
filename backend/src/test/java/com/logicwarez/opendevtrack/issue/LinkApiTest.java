/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * Links between issues. Users of {@code project/users.sql}: mario admin of ODT and of WEB, luisa developer and paola
 * viewer of ODT only, ugo of another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class LinkApiTest {

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
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_audit.issue_change "
                + "CASCADE");
        for (String project : List.of("ODT", "WEB")) {
            mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"" + project + "\",\"name\":\"" + project + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"paola\",\"role\":\"viewer\"}"))
                .andExpect(status().isOk());
        for (String summary : List.of("Login fails", "Release 1.0", "Session expires")) {
            mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"" + summary + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(as(post("/api/projects/WEB/issues"), mario).content("{\"summary\":\"Landing page\"}"))
                .andExpect(status().isCreated());
    }

    private String link(SessionAuthentication who, String from, String relation, String to, int expected) throws Exception {
        return mvc.perform(as(post("/api/issues/" + from + "/links"), who)
                        .content("{\"relation\":\"" + relation + "\",\"issue\":\"" + to + "\"}"))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aLinkReadsFromBothEnds() throws Exception {
        link(luisa, "ODT-1", "blocks", "ODT-2", 201);
        link(luisa, "ODT-3", "relates", "odt-1", 201);
        mvc.perform(as(get("/api/issues/ODT-2/links"), paola))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].relation").value("blocked_by"))
                .andExpect(jsonPath("$[0].key").value("ODT-1"))
                .andExpect(jsonPath("$[0].summary").value("Login fails"))
                .andExpect(jsonPath("$[0].status").value("todo"))
                .andExpect(jsonPath("$[0]._perm.delete").value(false));
        mvc.perform(as(get("/api/issues/ODT-1/links"), luisa))
                .andExpect(jsonPath("$[*].relation").value(contains("blocks", "relates")))
                .andExpect(jsonPath("$[1].key").value("ODT-3"))
                .andExpect(jsonPath("$[0]._perm.delete").value(true));
        // The same link again, also read from the other end, is a conflict; the others are refused.
        link(luisa, "ODT-2", "blocked_by", "ODT-1", 409);
        link(luisa, "ODT-1", "relates", "ODT-3", 409);
        link(luisa, "ODT-1", "blocks", "ODT-1", 400);
        link(luisa, "ODT-1", "fixes", "ODT-2", 400);
        link(luisa, "ODT-1", "blocks", "ODT-99", 400);
        link(paola, "ODT-1", "blocks", "ODT-3", 403);
        link(ugo, "ODT-1", "blocks", "ODT-3", 404);
        mvc.perform(as(get("/api/issues/ODT-1/links"), ugo)).andExpect(status().isNotFound());
    }

    @Test
    void anIssueOfAProjectNotSeenStaysHidden() throws Exception {
        link(luisa, "ODT-1", "duplicates", "WEB-1", 400);
        link(mario, "ODT-1", "duplicates", "WEB-1", 201);
        mvc.perform(as(get("/api/issues/WEB-1/links"), mario))
                .andExpect(jsonPath("$[0].relation").value("duplicated_by"))
                .andExpect(jsonPath("$[0].key").value("ODT-1"));
        mvc.perform(as(get("/api/issues/ODT-1/links"), luisa)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theCandidatesAreFoundByKeyOrText() throws Exception {
        mvc.perform(as(get("/api/issues/ODT-1/links/candidates"), luisa))
                .andExpect(jsonPath("$[*].key").value(contains("ODT-3", "ODT-2")));
        mvc.perform(as(get("/api/issues/ODT-1/links/candidates").param("q", "odt-2"), luisa))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("ODT-2 Release 1.0"));
        mvc.perform(as(get("/api/issues/ODT-1/links/candidates").param("q", "SESS"), luisa))
                .andExpect(jsonPath("$[*].key").value(contains("ODT-3")));
        // The issue itself is never a candidate.
        mvc.perform(as(get("/api/issues/ODT-1/links/candidates").param("q", "1"), luisa))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void addingAndRemovingALinkIsInTheChangelogOfBoth() throws Exception {
        String id = JsonPath.read(link(luisa, "ODT-1", "blocks", "ODT-2", 201), "$.id");
        mvc.perform(as(delete("/api/issues/ODT-3/links/" + id), luisa)).andExpect(status().isNotFound());
        mvc.perform(as(delete("/api/issues/ODT-2/links/" + id), paola)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/issues/ODT-2/links/" + id), luisa)).andExpect(status().isNoContent());
        String count = "SELECT count(*) FROM odt_audit.issue_change WHERE field = 'links'";
        for (int i = 0; i < 100 && jdbc.queryForObject(count, Integer.class) < 4; i++) {
            Thread.sleep(50);
        }
        assertThat(jdbc.queryForList("SELECT c.old_value, c.new_value FROM odt_audit.issue_change c "
                + "JOIN odt_issue.issue i ON i.id = c.issue_id WHERE c.field = 'links' AND i.summary = 'Release 1.0' "
                + "ORDER BY c.changed_at, c.new_value NULLS LAST"))
                .extracting(r -> r.get("old_value") + " > " + r.get("new_value"))
                .containsExactly("null > blocked_by ODT-1", "blocked_by ODT-1 > null");
    }

    @Test
    void theLinksOfADeletedIssueGoWithIt() throws Exception {
        link(luisa, "ODT-1", "blocks", "ODT-2", 201);
        mvc.perform(as(delete("/api/issues/ODT-1"), mario)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/issues/ODT-2/links"), luisa)).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odt_issue.issue_link", Integer.class)).isZero();
    }
}
