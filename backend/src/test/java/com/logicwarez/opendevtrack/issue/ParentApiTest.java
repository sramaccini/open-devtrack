/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
 * Parent and subtasks (ADR-28; users in {@code project/users.sql}): mario is the admin of ODT and APP, luisa a
 * developer of both, paola a viewer of ODT. ODT-1 is an epic, ODT-2 a story of it, ODT-3 and ODT-4 subtasks of the
 * story.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class ParentApiTest {

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

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue CASCADE");
        jdbc.execute("TRUNCATE odt_audit.issue_change, odt_notification.message CASCADE");
        project("ODT", "OpenDevTrack");
        project("APP", "App");
        member("ODT", "luisa", "developer");
        member("ODT", "paola", "viewer");
        member("APP", "luisa", "developer");
        create("ODT", "{\"type\":\"epic\",\"summary\":\"Login\"}");
        create("ODT", "{\"type\":\"story\",\"summary\":\"Sign in with a password\",\"parent\":\"ODT-1\"}");
        create("ODT", "{\"type\":\"subtask\",\"summary\":\"Form\",\"parent\":\"odt-2\"}");
        create("ODT", "{\"type\":\"subtask\",\"summary\":\"Checks\",\"parent\":\"ODT-2\"}");
        TestInfrastructure.awaitListeners(jdbc);
    }

    private void project(String key, String name) throws Exception {
        mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"" + key + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated());
    }

    private void member(String project, String username, String role) throws Exception {
        mvc.perform(as(post("/api/projects/" + project + "/members"), mario)
                        .content("{\"username\":\"" + username + "\",\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk());
    }

    private void create(String project, String body) throws Exception {
        mvc.perform(as(post("/api/projects/" + project + "/issues"), luisa).content(body)).andExpect(status().isCreated());
    }

    private int version(String issue) throws Exception {
        return JsonPath.read(mvc.perform(as(get("/api/issues/" + issue), luisa)).andReturn().getResponse()
                .getContentAsString(), "$.version");
    }

    private List<String> search(String query) throws Exception {
        String body = mvc.perform(as(post("/api/search"), mario).content("{\"query\":\"" + query + "\",\"size\":50}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].key");
    }

    @Test
    void theIssueShowsItsParentAndItsChildren() throws Exception {
        mvc.perform(as(get("/api/issues/ODT-3"), paola))
                .andExpect(jsonPath("$.type").value("subtask"))
                .andExpect(jsonPath("$.parent.key").value("ODT-2"))
                .andExpect(jsonPath("$.parent.summary").value("Sign in with a password"))
                .andExpect(jsonPath("$.parent.type").value("story"))
                .andExpect(jsonPath("$._perm.child").value(false));
        mvc.perform(as(get("/api/issues/ODT-1"), luisa))
                .andExpect(jsonPath("$.parent").value(nullValue()))
                .andExpect(jsonPath("$._perm.child").value(true));
        mvc.perform(as(get("/api/issues/ODT-2/children"), paola))
                .andExpect(jsonPath("$.items[*].key").value(contains("ODT-3", "ODT-4")))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.done").value(0))
                .andExpect(jsonPath("$.progress.todo").value(2))
                .andExpect(jsonPath("$.childType").value("subtask"))
                .andExpect(jsonPath("$.keys").value("ODT-3, ODT-4"));
        mvc.perform(as(get("/api/issues/ODT-1/children"), paola))
                .andExpect(jsonPath("$.items[*].key").value(contains("ODT-2")))
                .andExpect(jsonPath("$.childType").value("task"));
        // The candidates of the field: epics for a story, the standard issues for a subtask, nothing for an epic.
        mvc.perform(as(get("/api/projects/ODT/parents?type=story"), luisa))
                .andExpect(jsonPath("$[*].key").value(contains("ODT-1")));
        mvc.perform(as(get("/api/projects/ODT/parents?type=subtask&q=password"), luisa))
                .andExpect(jsonPath("$[*].key").value(contains("ODT-2")))
                .andExpect(jsonPath("$[0].label").value("ODT-2 Sign in with a password"));
        mvc.perform(as(get("/api/projects/ODT/parents?type=epic"), luisa)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theLevelsAreKept() throws Exception {
        // A subtask always has a parent, and it is a standard issue; an epic has none; the parent is in the project.
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa).content("{\"type\":\"subtask\",\"summary\":\"Alone\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa)
                        .content("{\"type\":\"subtask\",\"summary\":\"Under an epic\",\"parent\":\"ODT-1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa)
                        .content("{\"type\":\"subtask\",\"summary\":\"Under a subtask\",\"parent\":\"ODT-3\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa)
                        .content("{\"type\":\"epic\",\"summary\":\"Epic of an epic\",\"parent\":\"ODT-1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa)
                        .content("{\"type\":\"task\",\"summary\":\"Task under a story\",\"parent\":\"ODT-2\"}"))
                .andExpect(status().isBadRequest());
        create("APP", "{\"type\":\"epic\",\"summary\":\"Epic of APP\"}");
        mvc.perform(as(post("/api/projects/ODT/issues"), luisa)
                        .content("{\"type\":\"task\",\"summary\":\"Elsewhere\",\"parent\":\"APP-1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(patch("/api/issues/ODT-2"), luisa).content("{\"parent\":\"ODT-2\",\"version\":" + version("ODT-2") + "}"))
                .andExpect(status().isBadRequest());
        // An issue with children keeps its level.
        mvc.perform(as(patch("/api/issues/ODT-2"), luisa).content("{\"type\":\"epic\",\"version\":" + version("ODT-2") + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(patch("/api/issues/ODT-1"), luisa).content("{\"type\":\"task\",\"version\":" + version("ODT-1") + "}"))
                .andExpect(status().isBadRequest());
        // The same level is fine: a story becomes a bug and keeps its epic and its subtasks.
        mvc.perform(as(patch("/api/issues/ODT-2"), luisa).content("{\"type\":\"bug\",\"version\":" + version("ODT-2") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent.key").value("ODT-1"));
        // A viewer does not change the parent.
        mvc.perform(as(patch("/api/issues/ODT-2"), paola).content("{\"parent\":null,\"version\":" + version("ODT-2") + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theParentChangesAsAFieldWithItsHistory() throws Exception {
        create("ODT", "{\"type\":\"epic\",\"summary\":\"Sign up\"}");
        mvc.perform(as(patch("/api/issues/ODT-2"), luisa).content("{\"parent\":\"ODT-5\",\"version\":" + version("ODT-2") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent.key").value("ODT-5"));
        mvc.perform(as(patch("/api/issues/ODT-2"), luisa).content("{\"parent\":null,\"version\":" + version("ODT-2") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent").value(nullValue()));
        // A subtask made a task leaves its parent, as in Jira; made a subtask again it needs one.
        mvc.perform(as(patch("/api/issues/ODT-4"), luisa).content("{\"type\":\"task\",\"version\":" + version("ODT-4") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent").value(nullValue()));
        mvc.perform(as(patch("/api/issues/ODT-4"), luisa).content("{\"type\":\"subtask\",\"version\":" + version("ODT-4") + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(patch("/api/issues/ODT-4"), luisa)
                        .content("{\"type\":\"subtask\",\"parent\":\"ODT-2\",\"version\":" + version("ODT-4") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent.key").value("ODT-2"));
        TestInfrastructure.awaitListeners(jdbc);
        assertThat(jdbc.queryForList("SELECT coalesce(old_value, '-') || '>' || coalesce(new_value, '-')"
                + " FROM odt_audit.issue_change c JOIN odt_issue.issue i ON i.id = c.issue_id"
                + " WHERE c.field = 'parent' AND i.number = 2 ORDER BY c.id", String.class))
                .containsExactly("-" + ">ODT-1", "ODT-1>ODT-5", "ODT-5>-");
    }

    @Test
    void odqlFindsTheChildren() throws Exception {
        assertThat(search("parent = ODT-2 ORDER BY key")).containsExactly("ODT-3", "ODT-4");
        assertThat(search("parent IN (ODT-1, ODT-2) ORDER BY key")).containsExactlyInAnyOrder("ODT-2", "ODT-3", "ODT-4");
        assertThat(search("project = ODT AND parent IS EMPTY")).containsExactly("ODT-1");
        assertThat(search("type = subtask")).containsExactlyInAnyOrder("ODT-3", "ODT-4");
        assertThat(search("project = ODT AND parent != ODT-2")).containsExactlyInAnyOrder("ODT-1", "ODT-2");
    }

    @Test
    void odqlHasTheTypeFunctionsOfJira() throws Exception {
        assertThat(search("type IN subTaskIssueTypes() ORDER BY key")).containsExactly("ODT-3", "ODT-4");
        assertThat(search("type IN standardIssueTypes() ORDER BY key")).containsExactly("ODT-1", "ODT-2");
        assertThat(search("project = ODT AND type NOT IN (subTaskIssueTypes(), epic)")).containsExactly("ODT-2");
        mvc.perform(as(post("/api/search"), mario).content("{\"query\":\"type IN openSprints()\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theBacklogPanelListsTheEpicsAndSetsThem() throws Exception {
        create("ODT", "{\"type\":\"epic\",\"summary\":\"Sign up\"}");
        create("ODT", "{\"type\":\"task\",\"summary\":\"Captcha\"}");
        create("ODT", "{\"type\":\"bug\",\"summary\":\"Typo\"}");
        mvc.perform(as(get("/api/projects/ODT/epics"), paola))
                .andExpect(jsonPath("$[*].key").value(contains("ODT-1", "ODT-5")))
                .andExpect(jsonPath("$[0].progress").value("0/1"))
                .andExpect(jsonPath("$[1].total").value(0))
                .andExpect(jsonPath("$[1].label").value("ODT-5 Sign up"));
        mvc.perform(as(get("/api/projects/ODT/epics?q=sign"), paola)).andExpect(jsonPath("$[*].key").value(contains("ODT-5")));
        // Developers set the epic of several issues together; a viewer does not.
        mvc.perform(as(post("/api/projects/ODT/epic"), paola).content("{\"issues\":[\"ODT-6\"],\"epic\":\"ODT-5\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/projects/ODT/epic"), luisa).content("{\"issues\":[\"ODT-6\",\"ODT-7\"],\"epic\":\"ODT-5\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(2));
        mvc.perform(as(get("/api/issues/ODT-7"), luisa)).andExpect(jsonPath("$.parent.key").value("ODT-5"));
        // All or none: a subtask, an epic or an issue of another project is refused, and nothing changes.
        mvc.perform(as(post("/api/projects/ODT/epic"), luisa).content("{\"issues\":[\"ODT-2\",\"ODT-3\"],\"epic\":\"ODT-5\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/issues/ODT-2"), luisa)).andExpect(jsonPath("$.parent.key").value("ODT-1"));
        mvc.perform(as(post("/api/projects/ODT/epic"), luisa).content("{\"issues\":[\"ODT-6\"],\"epic\":\"ODT-2\"}"))
                .andExpect(status().isBadRequest());
        // Without an epic they leave it; the search rows carry the parent.
        mvc.perform(as(post("/api/projects/ODT/epic"), luisa).content("{\"issues\":[\"ODT-7\"]}"))
                .andExpect(jsonPath("$.changed").value(1));
        // No wait for the listeners: the search has the new parent as soon as the call answers, as the backlog needs.
        String rows = mvc.perform(as(post("/api/search"), luisa).content("{\"query\":\"parent = ODT-5\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(rows, "$.items[*].key")).containsExactly("ODT-6");
        assertThat(JsonPath.<List<String>>read(rows, "$.items[*].parent")).containsExactly("ODT-5");
        assertThat(JsonPath.<List<Boolean>>read(rows, "$.items[*]._perm.edit")).containsExactly(true);
    }

    @Test
    void aSubtaskHasNoStoryPoints() throws Exception {
        mvc.perform(as(put("/api/issues/ODT-3/plan"), luisa).content("{\"points\":3}")).andExpect(status().isBadRequest());
        mvc.perform(as(put("/api/issues/ODT-3/plan"), luisa).content("{\"points\":null}")).andExpect(status().isOk());
        // A task with points that becomes a subtask loses them.
        create("ODT", "{\"type\":\"task\",\"summary\":\"Estimated\"}");
        mvc.perform(as(put("/api/issues/ODT-5/plan"), luisa).content("{\"points\":5}")).andExpect(status().isOk());
        mvc.perform(as(patch("/api/issues/ODT-5"), luisa)
                        .content("{\"type\":\"subtask\",\"parent\":\"ODT-2\",\"version\":" + version("ODT-5") + "}"))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/issues/ODT-5/plan"), luisa)).andExpect(jsonPath("$.points").value(nullValue()));
    }

    @Test
    void deletingAParentDeletesItsSubtasksAndFreesTheChildrenOfAnEpic() throws Exception {
        mvc.perform(as(delete("/api/issues/ODT-1"), mario)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/issues/ODT-2"), luisa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent").value(nullValue()));
        mvc.perform(as(delete("/api/issues/ODT-2"), mario)).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/issues/ODT-3"), luisa)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/issues/ODT-4"), luisa)).andExpect(status().isNotFound());
        TestInfrastructure.awaitListeners(jdbc);
        assertThat(search("project = ODT")).isEmpty();
    }

    @Test
    void aStoryMovesWithItsSubtasksAndAnEpicAlone() throws Exception {
        // A subtask does not move alone.
        mvc.perform(as(post("/api/issues/ODT-3/move"), mario)
                        .content("{\"project\":\"APP\",\"version\":" + version("ODT-3") + "}"))
                .andExpect(status().isConflict());
        // The dry run names the subtasks that follow and the parent that is lost.
        mvc.perform(as(post("/api/issues/ODT-2/move"), mario).content("{\"project\":\"APP\",\"dryRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtasks").value(contains("ODT-3", "ODT-4")))
                .andExpect(jsonPath("$.cleared").value(contains("parent")))
                .andExpect(jsonPath("$.lostText").value("Parent (ODT-1)"));
        mvc.perform(as(post("/api/issues/ODT-2/move"), mario)
                        .content("{\"project\":\"APP\",\"version\":" + version("ODT-2") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("APP-1"));
        mvc.perform(as(get("/api/issues/APP-1/children"), luisa))
                .andExpect(jsonPath("$.items[*].key").value(contains("APP-2", "APP-3")));
        mvc.perform(as(get("/api/issues/ODT-3"), luisa))
                .andExpect(jsonPath("$.key").value("APP-2"))
                .andExpect(jsonPath("$.parent.key").value("APP-1"));
        mvc.perform(as(get("/api/issues/APP-1"), luisa)).andExpect(jsonPath("$.parent").value(nullValue()));
        TestInfrastructure.awaitListeners(jdbc);
        // The old key of the parent finds the children.
        assertThat(search("parent = ODT-2 ORDER BY key")).containsExactly("APP-2", "APP-3");
        // An epic moves alone: its children stay and lose it.
        create("ODT", "{\"type\":\"story\",\"summary\":\"Remember me\",\"parent\":\"ODT-1\"}");
        mvc.perform(as(post("/api/issues/ODT-1/move"), mario).content("{\"project\":\"APP\",\"dryRun\":true}"))
                .andExpect(jsonPath("$.detached").value(contains("ODT-5")));
        mvc.perform(as(post("/api/issues/ODT-1/move"), mario)
                        .content("{\"project\":\"APP\",\"version\":" + version("ODT-1") + "}"))
                .andExpect(status().isOk());
        mvc.perform(as(get("/api/issues/ODT-5"), luisa)).andExpect(jsonPath("$.parent").value(nullValue()));
    }

    @Test
    void theExportGivesTheParentAndTheChildren() throws Exception {
        mvc.perform(as(get("/api/issues/ODT-2/export"), paola))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.parent.key").value("ODT-1"))
                .andExpect(jsonPath("$.children[*].key").value(contains("ODT-3", "ODT-4")));
    }
}
