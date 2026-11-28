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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The link types of a tenant (ADR-29). Users of {@code project/users.sql}: mario admin of ODT and of the tenant, luisa
 * developer of ODT, ugo of another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class LinkTypeApiTest {

    private static final String ORIGIN = "http://localhost:8090";
    private static final List<String> MEMBER = List.of("odt.project.browse", "odt.user.browse");
    private static final List<String> ADMIN = List.of("odt.project.browse", "odt.user.browse", "odt.project.create",
            "odt.project.admin");

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

    private final SessionAuthentication mario = session("mario", "ALPHA", ADMIN);
    private final SessionAuthentication luisa = session("luisa", "ALPHA", MEMBER);
    private final SessionAuthentication ugo = session("ugo", "BETA", ADMIN);

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_audit.issue_change, "
                + "odt_issue.link_type CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"ODT\",\"name\":\"ODT\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        for (String summary : List.of("Login fails", "Release 1.0", "Session expires")) {
            mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"summary\":\"" + summary + "\"}"))
                    .andExpect(status().isCreated());
        }
    }

    private void link(String from, String relation, String to, int expected) throws Exception {
        mvc.perform(as(post("/api/issues/" + from + "/links"), luisa)
                        .content("{\"relation\":\"" + relation + "\",\"issue\":\"" + to + "\"}"))
                .andExpect(status().is(expected));
    }

    @Test
    void aTenantStartsWithTheFourTypesInTheLanguageOfTheUser() throws Exception {
        mvc.perform(as(get("/api/link-types"), luisa))
                .andExpect(jsonPath("$[*].code").value(contains("blocks", "clones", "duplicates", "relates")))
                .andExpect(jsonPath("$[0].inward").value("is blocked by"))
                .andExpect(jsonPath("$[0].system").value(true))
                .andExpect(jsonPath("$[0]._perm.edit").value(false));
        mvc.perform(as(get("/api/link-types"), mario))
                .andExpect(jsonPath("$[1]._perm.remove").value(false))
                .andExpect(jsonPath("$[2]._perm.remove").value(true));
        // Where to move the links of a type removed: the others.
        mvc.perform(as(get("/api/link-types").param("except", "duplicates"), mario))
                .andExpect(jsonPath("$[*].code").value(contains("blocks", "clones", "relates")));
        // One relation per end, relates once; the labels follow the language while the descriptions are as made.
        mvc.perform(as(get("/api/link-types/relations"), luisa).header("Accept-Language", "it"))
                .andExpect(jsonPath("$[*].relation").value(contains("blocks", "blocked_by", "clones", "cloned_by",
                        "duplicates", "duplicated_by", "relates")))
                .andExpect(jsonPath("$[0].label").value("blocca"));
        mvc.perform(as(get("/api/link-types/relations").param("q", "dup"), luisa))
                .andExpect(jsonPath("$[*].relation").value(contains("duplicates", "duplicated_by")));
        link("ODT-1", "blocks", "ODT-2", 201);
        mvc.perform(as(get("/api/issues/ODT-2/links"), luisa))
                .andExpect(jsonPath("$[0].relation").value("blocked_by"))
                .andExpect(jsonPath("$[0].type").value("blocks"))
                .andExpect(jsonPath("$[0].outward").value(false))
                .andExpect(jsonPath("$[0].label").value("is blocked by"));
    }

    @Test
    void theAdminOfTheTenantAddsAndChangesATypeThatLinksAsTheOthers() throws Exception {
        String causes = "{\"code\":\"causes\",\"outward\":\"causes\",\"inward\":\"is caused by\"}";
        mvc.perform(as(post("/api/link-types"), luisa).content(causes)).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/link-types"), mario).content("{\"code\":\"Causes\",\"outward\":\"a\",\"inward\":\"b\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/link-types"), mario).content("{\"code\":\"blocked_by\",\"outward\":\"a\",\"inward\":\"b\"}"))
                .andExpect(status().isConflict());
        mvc.perform(as(post("/api/link-types"), mario).content("{\"code\":\"causes\",\"outward\":\" \",\"inward\":\"b\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/link-types"), mario).content(causes))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(as(post("/api/link-types"), mario).content(causes)).andExpect(status().isConflict());
        link("ODT-1", "causes", "ODT-2", 201);
        link("ODT-2", "causes:inward", "ODT-1", 409);
        link("ODT-3", "causes:inward", "ODT-1", 201);
        mvc.perform(as(get("/api/issues/ODT-1/links"), luisa))
                .andExpect(jsonPath("$[*].relation").value(contains("causes", "causes")))
                .andExpect(jsonPath("$[0].label").value("causes"));
        mvc.perform(as(get("/api/issues/ODT-2/links"), luisa).header("Accept-Language", "it"))
                .andExpect(jsonPath("$[0].relation").value("causes:inward"))
                .andExpect(jsonPath("$[0].label").value("is caused by"));
        // New descriptions, with the version read; a type in use keeps being read differently from its two ends.
        mvc.perform(as(patch("/api/link-types/causes"), mario).content("{\"outward\":\"leads to\",\"inward\":\"is led to by\",\"version\":3}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(patch("/api/link-types/causes"), mario).content("{\"outward\":\"same\",\"inward\":\"same\",\"version\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(as(patch("/api/link-types/causes"), mario).content("{\"outward\":\"leads to\",\"inward\":\"is led to by\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.links").value(2));
        mvc.perform(as(get("/api/issues/ODT-2/links"), luisa)).andExpect(jsonPath("$[0].label").value("is led to by"));
        // A system type changed shows its new descriptions, in any language.
        mvc.perform(as(patch("/api/link-types/relates"), mario).content("{\"outward\":\"is near\",\"inward\":\"is near\",\"version\":0}"))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/link-types/relations").param("q", "near"), luisa).header("Accept-Language", "it"))
                .andExpect(jsonPath("$[*].label").value(contains("is near")));
        // Another tenant has its own types.
        mvc.perform(as(get("/api/link-types"), ugo)).andExpect(jsonPath("$.length()").value(4));
        mvc.perform(as(patch("/api/link-types/causes"), ugo).content("{\"outward\":\"a\",\"inward\":\"b\",\"version\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aTypeInUseGoesWithItsLinksMovedOrDeleted() throws Exception {
        mvc.perform(as(post("/api/link-types"), mario).content("{\"code\":\"causes\",\"outward\":\"causes\",\"inward\":\"is caused by\"}"))
                .andExpect(status().isCreated());
        link("ODT-1", "causes", "ODT-2", 201);
        link("ODT-1", "blocks", "ODT-3", 201);
        link("ODT-1", "causes", "ODT-3", 201);
        mvc.perform(as(delete("/api/link-types/clones"), mario)).andExpect(status().isConflict());
        mvc.perform(as(delete("/api/link-types/causes"), luisa)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/link-types/causes"), mario)).andExpect(status().isConflict());
        mvc.perform(as(delete("/api/link-types/causes").param("replaceWith", "causes"), mario))
                .andExpect(status().isBadRequest());
        // Moved to blocks: ODT-1 → ODT-2 becomes «blocks», ODT-1 → ODT-3 would copy a link there, and goes.
        mvc.perform(as(delete("/api/link-types/causes").param("replaceWith", "blocks"), mario))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/issues/ODT-1/links"), luisa))
                .andExpect(jsonPath("$[*].relation").value(contains("blocks", "blocks")))
                .andExpect(jsonPath("$[*].key").value(contains("ODT-2", "ODT-3")));
        mvc.perform(as(get("/api/link-types"), mario)).andExpect(jsonPath("$.length()").value(4));
        String count = "SELECT count(*) FROM odt_audit.issue_change WHERE field = 'links'";
        for (int i = 0; i < 100 && jdbc.queryForObject(count, Integer.class) < 10; i++) {
            Thread.sleep(50);
        }
        assertThat(jdbc.queryForList("SELECT c.old_value, c.new_value FROM odt_audit.issue_change c "
                + "JOIN odt_issue.issue i ON i.id = c.issue_id WHERE c.field = 'links' AND i.summary = 'Release 1.0' "
                + "AND c.old_value IS NOT NULL"))
                .extracting(r -> r.get("old_value") + " > " + r.get("new_value"))
                .containsExactly("causes:inward ODT-1 > blocked_by ODT-1");
        // Deleted with its links.
        link("ODT-2", "duplicates", "ODT-3", 201);
        mvc.perform(as(delete("/api/link-types/duplicates").param("deleteLinks", "true"), mario))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/issues/ODT-3/links"), luisa))
                .andExpect(jsonPath("$[*].relation").value(contains("blocked_by")));
    }
}
