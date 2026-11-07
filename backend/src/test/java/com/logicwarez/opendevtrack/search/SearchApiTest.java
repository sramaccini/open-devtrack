/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.function.BooleanSupplier;
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
 * Search with ODQL and the saved filters (ADR-16), on the index written after the commit. Users of
 * {@code project/users.sql}: mario admin of ODT and WEB, luisa developer of ODT only, ugo of another tenant.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@Sql("/project/users.sql")
class SearchApiTest {

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

    @Autowired
    Searches searches;

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
    private final SessionAuthentication ugo = session("ugo", "BETA", CREATOR);

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        jdbc.execute("TRUNCATE odt_project.project, odt_access.project_member, odt_issue.issue, odt_agile.issue_plan, "
                + "odt_agile.sprint, odt_agile.sprint_issue, odt_agile.board, odt_agile.board_column, "
                + "odt_search.issue_doc, odt_search.filter CASCADE");
        for (String project : List.of("ODT", "WEB")) {
            mvc.perform(as(post("/api/projects"), mario).content("{\"key\":\"" + project + "\",\"name\":\"" + project + "\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(as(post("/api/projects"), ugo).content("{\"key\":\"ODT\",\"name\":\"Other\"}"))
                .andExpect(status().isCreated());
        mvc.perform(as(post("/api/projects/ODT/members"), mario).content("{\"username\":\"luisa\",\"role\":\"developer\"}"))
                .andExpect(status().isOk());
        issue(mario, "ODT", """
                {"summary":"Login fails","type":"bug","priority":"highest","assignee":"luisa","labels":["backend"]}""");
        issue(mario, "ODT", """
                {"summary":"Checkout with a coupon","type":"story","description":"Il caffè è offerto perché sì"}""");
        issue(luisa, "ODT", """
                {"summary":"50% off banner","type":"task","priority":"low","labels":["frontend","promo"],
                 "custom":{}}""");
        issue(mario, "WEB", """
                {"summary":"Login page colours","type":"bug"}""");
        issue(ugo, "ODT", """
                {"summary":"Login of another tenant","type":"bug"}""");
        await(() -> docs() == 5);
    }

    private void issue(SessionAuthentication who, String project, String body) throws Exception {
        mvc.perform(as(post("/api/projects/" + project + "/issues"), who).content(body)).andExpect(status().isCreated());
    }

    private int docs() {
        return jdbc.queryForObject("SELECT count(*) FROM odt_search.issue_doc", Integer.class);
    }

    /** The index is written after the commit, on another thread: wait until it shows what is expected. */
    private static void await(BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 100 && !done.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        assertThat(done.getAsBoolean()).isTrue();
    }

    private List<String> keys(SessionAuthentication who, String query) throws Exception {
        String body = mvc.perform(as(post("/api/search"), who).content("{\"query\":" + json(query) + ",\"size\":50}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.items[*].key");
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void queriesFindTheIssuesOfTheProjectsTheUserSees() throws Exception {
        assertThat(keys(mario, "type = bug ORDER BY key")).containsExactly("ODT-1", "WEB-1");
        assertThat(keys(luisa, "type = bug")).containsExactly("ODT-1");
        assertThat(keys(luisa, "project = WEB")).isEmpty();
        assertThat(keys(ugo, "summary ~ login")).containsExactly("ODT-1");
        assertThat(keys(mario, "assignee = luisa")).containsExactly("ODT-1");
        assertThat(keys(luisa, "assignee = currentUser()")).containsExactly("ODT-1");
        assertThat(keys(mario, "priority >= high")).containsExactly("ODT-1");
        assertThat(keys(mario, "priority < medium")).containsExactly("ODT-3");
        assertThat(keys(mario, "labels IN (promo, backend) ORDER BY key DESC")).containsExactly("ODT-3", "ODT-1");
        assertThat(keys(mario, "labels IS EMPTY ORDER BY key")).containsExactly("ODT-2", "WEB-1");
        assertThat(keys(mario, "summary ~ \"50%\"")).containsExactly("ODT-3");
        // Full text without accents, on the start of the words, through the description too.
        assertThat(keys(mario, "text ~ \"caffe perche\"")).containsExactly("ODT-2");
        assertThat(keys(mario, "text ~ caf")).containsExactly("ODT-2");
        assertThat(keys(mario, "status = \"To do\" AND created >= today ORDER BY key")).hasSize(4);
        assertThat(keys(mario, "reporter = luisa OR NOT (type = bug OR type = task) ORDER BY key"))
                .containsExactly("ODT-2", "ODT-3");
    }

    @Test
    void theIndexFollowsChangesPlansAndDeletions() throws Exception {
        String issue = mvc.perform(as(get("/api/issues/ODT-1"), mario)).andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(issue, "$.version");
        mvc.perform(as(post("/api/issues/ODT-1/transitions"), mario).content("{\"transition\":\"start\",\"version\":"
                + version + "}")).andExpect(status().isOk());
        await(() -> jdbc.queryForObject("SELECT category FROM odt_search.issue_doc WHERE tenant_id = 'ALPHA' AND issue_key = 'ODT-1'",
                String.class).equals("in_progress"));
        assertThat(keys(mario, "category = in_progress")).containsExactly("ODT-1");
        assertThat(keys(mario, "status = \"In corso\"")).containsExactly("ODT-1");
        version = JsonPath.read(mvc.perform(as(get("/api/issues/ODT-1"), mario)).andReturn().getResponse()
                .getContentAsString(), "$.version");
        mvc.perform(as(post("/api/issues/ODT-1/transitions"), mario).content("{\"transition\":\"finish\",\"version\":"
                + version + "}")).andExpect(status().isOk());
        await(() -> jdbc.queryForObject("SELECT resolved_at IS NOT NULL FROM odt_search.issue_doc WHERE tenant_id = 'ALPHA' AND issue_key = 'ODT-1'",
                Boolean.class));
        assertThat(keys(mario, "resolved >= today")).containsExactly("ODT-1");

        mvc.perform(as(put("/api/issues/ODT-2/plan"), mario).content("{\"points\":5}")).andExpect(status().isOk());
        await(() -> jdbc.queryForObject("SELECT points FROM odt_search.issue_doc WHERE tenant_id = 'ALPHA' AND issue_key = 'ODT-2'",
                Integer.class) != null);
        assertThat(keys(mario, "points >= 3")).containsExactly("ODT-2");

        String sprint = mvc.perform(as(post("/api/projects/ODT/sprints"), mario).content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String sprintId = JsonPath.read(sprint, "$.id");
        String sprintName = JsonPath.read(sprint, "$.name");
        mvc.perform(as(post("/api/projects/ODT/backlog/cards/ODT-3/move"), mario).content("{\"column\":\"" + sprintId + "\"}"))
                .andExpect(status().isOk());
        await(() -> jdbc.queryForObject("SELECT sprint_id IS NOT NULL FROM odt_search.issue_doc WHERE tenant_id = 'ALPHA' AND issue_key = 'ODT-3'",
                Boolean.class));
        assertThat(keys(mario, "sprint IN openSprints()")).containsExactly("ODT-3");
        assertThat(keys(mario, "sprint = \"" + sprintName + "\"")).containsExactly("ODT-3");
        assertThat(keys(mario, "project = ODT AND sprint IS EMPTY ORDER BY rank")).containsExactly("ODT-1", "ODT-2");

        mvc.perform(as(delete("/api/issues/ODT-2"), mario)).andExpect(status().isNoContent());
        await(() -> docs() == 4);
    }

    @Test
    void dueDatesAndReviewersAreSearched() throws Exception {
        String yesterday = searches.today().minusDays(1).toString();
        int version = JsonPath.read(mvc.perform(as(get("/api/issues/ODT-1"), mario)).andReturn().getResponse()
                .getContentAsString(), "$.version");
        mvc.perform(as(put("/api/issues/ODT-1"), mario).content("""
                        {"summary":"Login fails","type":"bug","priority":"highest","assignee":"luisa","reviewer":"mario",
                         "dueOn":"%s","labels":["backend"],"version":%d}""".formatted(yesterday, version)))
                .andExpect(status().isOk());
        await(() -> jdbc.queryForObject("SELECT reviewer IS NOT NULL FROM odt_search.issue_doc WHERE tenant_id = 'ALPHA'"
                + " AND issue_key = 'ODT-1'", Boolean.class));
        assertThat(keys(mario, "reviewer = currentUser()")).containsExactly("ODT-1");
        assertThat(keys(mario, "due < today")).containsExactly("ODT-1");
        assertThat(keys(mario, "due IS EMPTY ORDER BY key")).containsExactly("ODT-2", "ODT-3", "WEB-1");
        mvc.perform(as(post("/api/search"), mario).content("{\"query\":\"key = ODT-1\"}"))
                .andExpect(jsonPath("$.items[0].due").value(yesterday))
                .andExpect(jsonPath("$.items[0].reviewer").value("mario"))
                .andExpect(jsonPath("$.items[0].overdue").value("overdue"));
        // The personal dashboard: overdue for the assignee; in review for the reviewer once the work has started.
        mvc.perform(as(get("/api/me/summary"), luisa)).andExpect(jsonPath("$.overdue").value(1));
        mvc.perform(as(get("/api/me/summary"), mario)).andExpect(jsonPath("$.reviewing").value(0));
        version = JsonPath.read(mvc.perform(as(get("/api/issues/ODT-1"), mario)).andReturn().getResponse()
                .getContentAsString(), "$.version");
        mvc.perform(as(post("/api/issues/ODT-1/transitions"), mario).content("{\"transition\":\"start\",\"version\":"
                + version + "}")).andExpect(status().isOk());
        await(() -> jdbc.queryForObject("SELECT category FROM odt_search.issue_doc WHERE tenant_id = 'ALPHA'"
                + " AND issue_key = 'ODT-1'", String.class).equals("in_progress"));
        mvc.perform(as(get("/api/me/summary"), mario)).andExpect(jsonPath("$.reviewing").value(1));
    }

    @Test
    void theTableGroupsFiltersAndSorts() throws Exception {
        mvc.perform(as(post("/api/search"), mario).content("""
                        {"query":"project = ODT","groupBy":["type"],"groupPath":[]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.groups[0].value").value("bug"))
                .andExpect(jsonPath("$.groups[0].count").value(1));
        mvc.perform(as(post("/api/search"), mario).content("""
                        {"query":"project = ODT","groupBy":["type"],"groupPath":[{"field":"type","value":"story"}]}"""))
                .andExpect(jsonPath("$.items[0].key").value("ODT-2"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(as(post("/api/search"), mario).content("""
                        {"query":"","filters":[{"field":"summary","kind":"text","text":"login"},
                                               {"field":"project","kind":"select","values":["WEB"]}]}"""))
                .andExpect(jsonPath("$.items[*].key").value(org.hamcrest.Matchers.contains("WEB-1")));
        mvc.perform(as(post("/api/search"), mario).content("""
                        {"query":"","sort":{"field":"key","direction":"asc"},"page":1,"size":2}"""))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[*].key").value(org.hamcrest.Matchers.contains("ODT-3", "WEB-1")));
        mvc.perform(as(post("/api/search"), mario).content("{\"query\":\"\",\"distinct\":\"type\"}"))
                .andExpect(jsonPath("$.values").value(org.hamcrest.Matchers.contains("bug", "story", "task")));
        mvc.perform(as(post("/api/search"), mario).content("{\"query\":\"\",\"groupBy\":[\"summary\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/search"), mario).content("{\"query\":\"status IN (todo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Expected )"))
                .andExpect(jsonPath("$.position").value(16))
                .andExpect(jsonPath("$.expected").value(")"));
        mvc.perform(as(get("/api/search/check").param("q", "colour = red"), mario))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.code").value("odql"))
                .andExpect(jsonPath("$.params.position").value(1))
                .andExpect(jsonPath("$.params.reason").value("Unknown field 'colour'"));
        mvc.perform(as(get("/api/search/check").param("q", "type = bug"), mario))
                .andExpect(jsonPath("$.valid").value(true));
    }

    @Test
    void suggestionsFollowTheQuery() throws Exception {
        mvc.perform(as(get("/api/search/words").param("q", "sta").param("word", "sta"), mario))
                .andExpect(jsonPath("$[*].insert").value(org.hamcrest.Matchers.contains("status ")));
        mvc.perform(as(get("/api/search/words").param("q", "status ").param("word", ""), mario))
                .andExpect(jsonPath("$[*].label").value(org.hamcrest.Matchers.hasItems("=", "IN (", "IS EMPTY")));
        mvc.perform(as(get("/api/search/words").param("q", "status = ").param("word", ""), mario))
                .andExpect(jsonPath("$[*].insert").value(org.hamcrest.Matchers.contains("todo ", "in_progress ", "done ")));
        mvc.perform(as(get("/api/search/words").param("q", "type = bug ").param("word", ""), mario))
                .andExpect(jsonPath("$[*].label").value(org.hamcrest.Matchers.contains("AND", "OR", "ORDER BY")));
        mvc.perform(as(get("/api/search/words").param("q", "labels IN (fr").param("word", "fr"), mario))
                .andExpect(jsonPath("$[*].insert").value(org.hamcrest.Matchers.contains("frontend ")));
        mvc.perform(as(get("/api/search/words").param("q", "project=").param("word", "project="), luisa))
                .andExpect(jsonPath("$[*].insert").value(org.hamcrest.Matchers.contains("project=ODT ")));
    }

    @Test
    void savedFiltersArePrivateOrShared() throws Exception {
        String own = mvc.perform(as(post("/api/filters"), luisa).content("""
                        {"name":"My bugs","query":"type = bug AND assignee = currentUser()","onDashboard":true}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$._perm.edit").value(true))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(as(post("/api/filters"), mario).content("""
                        {"name":"All bugs","query":"type = bug","shared":true}""")).andExpect(status().isCreated());
        String marioPrivate = mvc.perform(as(post("/api/filters"), mario).content("""
                        {"name":"Mine","query":"reporter = currentUser()"}"""))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(as(post("/api/filters"), mario).content("{\"name\":\"Wrong\",\"query\":\"type = \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.position").value(8));
        mvc.perform(as(get("/api/filters"), luisa))
                .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.contains("All bugs", "My bugs")))
                .andExpect(jsonPath("$[0]._perm.edit").value(false));
        mvc.perform(as(get("/api/filters"), ugo)).andExpect(jsonPath("$.length()").value(0));
        // Someone else's filter, even shared, is not found for a change.
        String marioId = JsonPath.read(marioPrivate, "$.id");
        mvc.perform(as(delete("/api/filters/" + marioId), luisa)).andExpect(status().isNotFound());
        String id = JsonPath.read(own, "$.id");
        mvc.perform(as(put("/api/filters/" + id), luisa).content("""
                        {"name":"My open bugs","query":"type = bug AND category != done","onDashboard":false,"version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(as(put("/api/filters/" + id), luisa).content("""
                        {"name":"Again","query":"type = bug","version":0}"""))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(as(delete("/api/filters/" + id), luisa)).andExpect(status().isNoContent());
    }

    @Test
    void onlyTheAdminOfTheTenantRebuildsTheIndex() throws Exception {
        jdbc.execute("DELETE FROM odt_search.issue_doc WHERE project_key = 'WEB'");
        mvc.perform(as(post("/api/search/reindex"), luisa)).andExpect(status().isForbidden());
        SessionAuthentication admin = session("mario", "ALPHA", List.of("odt.project.browse", "odt.project.admin"));
        mvc.perform(as(post("/api/search/reindex"), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues").value(4));
        assertThat(docs()).isEqualTo(5);
    }
}
