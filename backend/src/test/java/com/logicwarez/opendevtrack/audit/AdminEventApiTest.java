/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The audit log of the administration (ADR-22): every logged action, the filters of the log of the tenant, the log of
 * a project, who may read them, no secret in the log, and the retention. Users in {@code project/users.sql}.
 */
@SpringBootTest(properties = {"odt.webhooks.allow-private=true", "odt.webhooks.delay=PT1H"})
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class AdminEventApiTest {

    private static final String ORIGIN = "http://localhost:8090";
    /** The path of a chat webhook is its secret: it must not reach the log. */
    private static final String HOOK = "http://127.0.0.1:9/services/T0001/B0002/s3cr3tT0k3n";

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AdminEvents adminEvents;

    MockMvc mvc;

    private final SessionAuthentication mario = session("mario", "ALPHA",
            List.of("odt.project.browse", "odt.user.browse", "odt.project.create"));
    private final SessionAuthentication luisa = session("luisa", "ALPHA", List.of("odt.project.browse", "odt.user.browse"));
    private final SessionAuthentication nora = session("nora", "ALPHA",
            List.of("odt.project.browse", "odt.user.browse", "odt.project.admin"));
    private final SessionAuthentication ugo = session("ugo", "BETA",
            List.of("odt.project.browse", "odt.user.browse", "odt.project.admin"));

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_access.user_group,"
                + " odt_access.permission_scheme, odt_audit.admin_event CASCADE");
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"WEB\",\"name\":\"Web\"}"))
                .andExpect(status().isCreated());
    }

    private static SessionAuthentication session(String username, String tenant, List<String> permissions) {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(username, username, username + "@example.org", tenant,
                List.of(new SessionContext.Tenant(tenant, tenant)), List.of(), permissions, now, now.plus(10, ChronoUnit.HOURS)));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, SessionAuthentication who) {
        return request.with(authentication(who)).with(csrf()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON);
    }

    private String body(MockHttpServletRequestBuilder request, SessionAuthentication who, int expected) throws Exception {
        return mvc.perform(as(request, who)).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void everyActionIsLogged() throws Exception {
        // the project and its members
        body(put("/api/projects/WEB").content("{\"name\":\"Web site\",\"version\":0}"), mario, 200);
        body(post("/api/projects/WEB/members").content("{\"username\":\"luisa\",\"role\":\"developer\"}"), mario, 200);
        body(put("/api/projects/WEB/members/luisa").content("{\"role\":\"admin\"}"), mario, 200);
        body(put("/api/projects/WEB/members/luisa").content("{\"role\":\"admin\"}"), mario, 200);
        body(delete("/api/projects/WEB/members/luisa"), mario, 204);

        // groups and permission schemes of the tenant, and their use in the project
        String group = JsonPath.read(body(post("/api/groups").content("{\"name\":\"Leads\"}"), nora, 201), "$.id");
        body(post("/api/groups/" + group + "/members").content("{\"username\":\"luisa\"}"), nora, 201);
        body(post("/api/projects/WEB/groups").content("{\"group\":\"" + group + "\",\"role\":\"viewer\"}"), mario, 200);
        body(delete("/api/projects/WEB/groups/" + group), mario, 204);
        String scheme = JsonPath.read(body(post("/api/permission-schemes").content("{\"name\":\"Strict\"}"), nora, 201), "$.id");
        body(put("/api/projects/WEB/permission-scheme").content("{\"scheme\":\"" + scheme + "\"}"), mario, 200);

        // workflow, board, custom field
        int version = JsonPath.read(body(get("/api/projects/WEB/workflow/statuses"), mario, 200), "$[0].version");
        body(post("/api/projects/WEB/workflow/statuses")
                .content("{\"code\":\"review\",\"category\":\"in_progress\",\"version\":" + version + "}"), mario, 201);
        body(post("/api/projects/WEB/boards").content("{\"name\":\"Team\"}"), mario, 201);
        body(post("/api/projects/WEB/field-definitions").content("{\"key\":\"team\",\"label\":\"Team\",\"type\":\"text\"}"),
                mario, 201);
        TestInfrastructure.awaitListeners(jdbc);

        // the log of the tenant, newest first; the role set twice to admin is logged once
        mvc.perform(as(get("/api/admin-events"), nora))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action", contains("customfield.created", "board.created",
                        "workflow.status_added", "project.scheme_changed", "scheme.created", "project.group_removed",
                        "project.group_added", "group.member_added", "group.created", "member.removed",
                        "member.role_changed", "member.added", "project.changed", "project.created")))
                .andExpect(jsonPath("$[10].target").value("luisa"))
                .andExpect(jsonPath("$[10].changes").value("role: developer → admin"))
                .andExpect(jsonPath("$[10].before.role").value("developer"))
                .andExpect(jsonPath("$[10].project").value("WEB"))
                .andExpect(jsonPath("$[12].changes").value("name: Web → Web site"))
                .andExpect(jsonPath("$[3].changes").value("scheme: Default → Strict"))
                .andExpect(jsonPath("$[8].project").doesNotExist());

        // the filters: project, user, kind (a whole kind with a trailing dot), days
        mvc.perform(as(get("/api/admin-events").param("actor", "nora"), nora))
                .andExpect(jsonPath("$[*].action", contains("scheme.created", "group.member_added", "group.created")));
        mvc.perform(as(get("/api/admin-events").param("action", "member."), nora))
                .andExpect(jsonPath("$[*].action", contains("member.removed", "member.role_changed", "member.added")));
        mvc.perform(as(get("/api/admin-events").param("action", "board.created").param("project", "WEB"), nora))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(as(get("/api/admin-events").param("project", "NOPE"), nora)).andExpect(jsonPath("$.length()").value(0));
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        mvc.perform(as(get("/api/admin-events").param("from", today).param("to", today), nora))
                .andExpect(jsonPath("$.length()").value(14));
        mvc.perform(as(get("/api/admin-events").param("to", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()), nora))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/admin-events").param("from", "yesterday"), nora)).andExpect(status().isBadRequest());

        // the log of a project: its admins only, the changes of the tenant left out
        mvc.perform(as(get("/api/projects/WEB/admin-events"), mario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$[*].project", everyItem(is("WEB"))));
        body(post("/api/projects/WEB/members").content("{\"username\":\"luisa\",\"role\":\"developer\"}"), mario, 200);
        body(get("/api/projects/WEB/admin-events"), luisa, 403);

        // the log of the tenant: the admin of the tenant only, and only their own tenant
        body(get("/api/admin-events"), mario, 403);
        mvc.perform(as(get("/api/admin-events"), ugo)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void noSecretIsLogged() throws Exception {
        String created = body(post("/api/projects/WEB/webhooks")
                .content("{\"name\":\"Chat\",\"url\":\"" + HOOK + "\",\"events\":[\"issue.created\"]}"), mario, 201);
        String id = JsonPath.read(created, "$.id");
        String first = JsonPath.read(created, "$.secret");
        String rotated = JsonPath.read(body(post("/api/projects/WEB/webhooks/" + id + "/secret"), mario, 200), "$.secret");
        body(put("/api/projects/WEB/webhooks/" + id)
                .content("{\"name\":\"Chat\",\"url\":\"" + HOOK + "x\",\"events\":[\"*\"],\"version\":1}"), mario, 200);
        body(delete("/api/projects/WEB/webhooks/" + id), mario, 204);
        TestInfrastructure.awaitListeners(jdbc);

        mvc.perform(as(get("/api/projects/WEB/admin-events").param("action", "webhook."), mario))
                .andExpect(jsonPath("$[*].action", hasItems("webhook.removed", "webhook.changed",
                        "webhook.secret_rotated", "webhook.created")))
                .andExpect(jsonPath("$[1].changes").value("events: [issue.created] → [*]"))
                .andExpect(jsonPath("$[2].after.secret").value("rotated"))
                .andExpect(jsonPath("$[3].after.secret").value("rotated"))
                .andExpect(jsonPath("$[3].after.url").value("http://127.0.0.1:9/…"));
        List<String> rows = jdbc.queryForList(
                "SELECT target || before::text || after::text FROM odt_audit.admin_event", String.class);
        assertThat(rows).isNotEmpty().noneMatch(r -> r.contains(first) || r.contains(rotated) || r.contains("s3cr3t")
                || r.contains("T0001"));
    }

    @Test
    void oldEventsAreDeletedEveryNight() throws Exception {
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.update("INSERT INTO odt_audit.admin_event (tenant_id, action, actor, occurred_at)"
                + " VALUES ('ALPHA', 'group.created', 'nora', now() - interval '731 days')");
        jdbc.update("INSERT INTO odt_audit.admin_event (tenant_id, action, actor, occurred_at)"
                + " VALUES ('ALPHA', 'group.removed', 'nora', now() - interval '700 days')");
        adminEvents.deleteOld();
        assertThat(jdbc.queryForList("SELECT action FROM odt_audit.admin_event ORDER BY occurred_at", String.class))
                .containsExactly("group.removed", "project.created");
    }

    @Test
    void changesInWords() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", "Team");
        before.put("description", null);
        before.put("lead", "mario");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", "Team");
        after.put("description", "Front end");
        after.put("columns", List.of("To do", "Done"));
        after.put("filter", null);
        assertThat(AdminEventController.changes(before, after))
                .isEqualTo("description: — → Front end; lead: mario; columns: [To do, Done]");
        assertThat(AdminEventController.changes(null, Map.of("role", "admin"))).isEqualTo("role: admin");
    }
}
