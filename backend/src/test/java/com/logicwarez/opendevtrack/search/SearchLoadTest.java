/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.logicwarez.authz.session.SessionAuthentication;
import com.logicwarez.authz.session.SessionContext;
import com.logicwarez.opendevtrack.TestInfrastructure;
import jakarta.servlet.Filter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The load test of ADR-16 point 8, outside the normal build ({@code mvn test -Dgroups=load -DexcludedGroups=none}):
 * one million issues over 200 projects, written with {@code generate_series} straight into the index of the search,
 * and the p95 of ten typical queries and of the suggestions, through the API as the screen calls it, for a member of
 * every project. The target of M3 is p95 below one second; the results go to {@code target/search-load.md}, and from
 * there to {@code docs/search-load.md}.
 */
@Tag("load")
@SpringBootTest
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchLoadTest {

    static final int ISSUES = 1_000_000;
    static final int PROJECTS = 200;
    static final int WARM_UP = 3;
    static final int RUNS = 20;
    static final long TARGET_MS = 1000;

    private static final String ORIGIN = "http://localhost:8090";
    private static final String TENANT = "LOAD";
    private static final String USER = "load.user";

    @Autowired
    WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    Filter security;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    private final SessionAuthentication user = session();

    private static SessionAuthentication session() {
        Instant now = Instant.now();
        return new SessionAuthentication(new SessionContext(USER, USER, USER + "@example.org", TENANT,
                List.of(new SessionContext.Tenant(TENANT, TENANT)), List.of(),
                List.of("odt.project.browse", "odt.user.browse"), now, now.plus(10, ChronoUnit.HOURS)));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.with(authentication(user)).with(csrf()).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeAll
    void fill() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        TestInfrastructure.awaitListeners(jdbc);
        long started = System.nanoTime();
        jdbc.execute("INSERT INTO tenant (code, description) VALUES ('" + TENANT + "', 'Load') ON CONFLICT DO NOTHING");
        jdbc.execute("DELETE FROM odt_search.issue_doc WHERE tenant_id = '" + TENANT + "'");
        jdbc.execute("DELETE FROM odt_access.project_member WHERE project_id IN "
                + "(SELECT id FROM odt_project.project WHERE tenant_id = '" + TENANT + "')");
        jdbc.execute("DELETE FROM odt_agile.sprint WHERE project_id IN "
                + "(SELECT id FROM odt_project.project WHERE tenant_id = '" + TENANT + "')");
        jdbc.execute("DELETE FROM odt_project.project WHERE tenant_id = '" + TENANT + "'");
        // 200 projects P001 … P200, the user a developer of each.
        jdbc.execute("""
                INSERT INTO odt_project.project (tenant_id, project_key, name, lead, issue_seq, created_by, created_at)
                SELECT 'LOAD', 'P' || lpad(p::text, 3, '0'), 'Project ' || p, 'load.user', 5000, 'load.user', now()
                FROM generate_series(1, 200) p""");
        jdbc.execute("""
                INSERT INTO odt_access.project_member (project_id, username, role, granted_by, granted_at)
                SELECT id, 'load.user', 'DEVELOPER', 'load.user', now() FROM odt_project.project WHERE tenant_id = 'LOAD'""");
        // Per project two completed sprints and an active one.
        jdbc.execute("""
                INSERT INTO odt_agile.sprint (project_id, name, state, starts_on, ends_on, created_at)
                SELECT p.id, p.project_key || ' Sprint ' || s, CASE WHEN s = 3 THEN 'active' ELSE 'completed' END,
                       current_date - (42 - 14 * s), current_date - (28 - 14 * s), now()
                FROM odt_project.project p, generate_series(1, 3) s WHERE p.tenant_id = 'LOAD'""");
        // One million issues, 5 000 per project: two thirds done, the rest to do or in progress; 500 assignees (the
        // user is one of them), 300 reporters, 40 labels, summaries from a vocabulary of 60 words, two years of dates.
        jdbc.execute("""
                WITH words AS (SELECT ARRAY['payment','timeout','checkout','login','invoice','refund','export','import',
                          'search','report','dashboard','sprint','board','mobile','android','ios','web','api','bank',
                          'card','coupon','discount','receipt','currency','tax','vat','order','cart','shipping',
                          'address','email','notification','password','session','token','cache','database','index',
                          'query','latency','crash','error','warning','upload','download','file','image','pdf','csv',
                          'excel','theme','dark','language','italian','english','user','admin','role','tenant',
                          'workflow'] AS w),
                     labels AS (SELECT ARRAY['backend','frontend','payments','mobile','api','security','performance',
                          'ux','docs','infra','billing','search','reports','auth','mail','i18n','a11y','legal','ops',
                          'data','ios','android','web','qa','design','support','sales','growth','core','platform',
                          'cloud','cache','db','queue','batch','import','export','cli','sdk','beta'] AS l),
                     projects AS (SELECT id, project_key, row_number() OVER (ORDER BY project_key) - 1 AS n
                          FROM odt_project.project WHERE tenant_id = 'LOAD'),
                     sprints AS (SELECT project_id, id FROM odt_agile.sprint WHERE state = 'active'),
                     g AS (SELECT i, i % 200 AS pn, i / 200 + 1 AS number,
                                  CASE WHEN i % 3 = 0 THEN 'todo' WHEN i % 7 = 1 THEN 'in_progress' ELSE 'done' END AS status
                           FROM generate_series(0, 999999) i)
                INSERT INTO odt_search.issue_doc (issue_id, tenant_id, project_id, project_key, issue_key, number, type,
                       status, category, priority, priority_order, assignee, reporter, labels, summary, created_at,
                       updated_at, resolved_at, sprint_id, points, rank, tsv)
                SELECT gen_random_uuid(), 'LOAD', p.id, p.project_key, p.project_key || '-' || g.number, g.number,
                       (ARRAY['task','bug','story','epic'])[1 + g.i % 4], g.status, g.status,
                       (ARRAY['lowest','low','medium','high','highest'])[1 + (g.i / 7) % 5], 1 + (g.i / 7) % 5,
                       CASE WHEN g.i % 10 = 0 THEN NULL WHEN g.i % 499 = 0 THEN 'load.user' ELSE 'user' || (g.i % 500) END,
                       CASE WHEN g.i % 997 = 0 THEN 'load.user' ELSE 'user' || (g.i % 300) END,
                       CASE WHEN g.i % 3 = 0 THEN ARRAY[]::text[]
                            WHEN g.i % 3 = 1 THEN ARRAY[l.l[1 + g.i % 40]]
                            ELSE ARRAY[l.l[1 + g.i % 40], l.l[1 + (g.i / 40) % 40]] END,
                       initcap(w.w[1 + g.i % 60]) || ' ' || w.w[1 + (g.i / 60) % 60] || ' ' || w.w[1 + (g.i / 3600) % 60],
                       now() - make_interval(days => (g.i % 730), mins => g.i % 1440),
                       now() - make_interval(days => (g.i % 61), mins => g.i % 1440),
                       CASE WHEN g.status = 'done' THEN now() - make_interval(days => (g.i % 59)) END,
                       CASE WHEN g.status <> 'done' AND g.number % 5 = 0 THEN s.id END,
                       CASE WHEN g.i % 4 = 0 THEN NULL ELSE (ARRAY[1,2,3,5,8,13])[1 + g.i % 6] END,
                       lpad(g.number::text, 10, '0'),
                       odt_search.text_vector(
                           initcap(w.w[1 + g.i % 60]) || ' ' || w.w[1 + (g.i / 60) % 60] || ' ' || w.w[1 + (g.i / 3600) % 60],
                           NULL,
                           CASE WHEN g.i % 3 = 0 THEN ARRAY[]::text[]
                                WHEN g.i % 3 = 1 THEN ARRAY[l.l[1 + g.i % 40]]
                                ELSE ARRAY[l.l[1 + g.i % 40], l.l[1 + (g.i / 40) % 40]] END)
                FROM g JOIN projects p ON p.n = g.pn LEFT JOIN sprints s ON s.project_id = p.id, words w, labels l""");
        jdbc.execute("ANALYZE odt_search.issue_doc");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odt_search.issue_doc WHERE tenant_id = 'LOAD'", Long.class))
                .isEqualTo(ISSUES);
        System.out.printf("search load: %d issues written in %d s%n", ISSUES, (System.nanoTime() - started) / 1_000_000_000);
    }

    /** The ten typical queries: a name and the ODQL. */
    static final Map<String, String> QUERIES = new LinkedHashMap<>();

    static {
        QUERIES.put("My open work", "assignee = currentUser() AND category != done ORDER BY priority DESC");
        QUERIES.put("One project, in progress", "project = P007 AND status = in_progress");
        QUERIES.put("Sprint of a project", "project = P042 AND sprint IN openSprints() ORDER BY rank");
        QUERIES.put("Urgent open bugs", "type = bug AND priority >= high AND category != done");
        QUERIES.put("Full text", "text ~ \"payment timeout\"");
        QUERIES.put("Summary contains", "summary ~ checkout");
        QUERIES.put("Label, updated this week", "labels = backend AND updated >= -7d");
        QUERIES.put("Created this month", "created >= -30d ORDER BY created DESC");
        QUERIES.put("Reported by me, resolved lately", "reporter = currentUser() AND resolved >= -14d");
        QUERIES.put("Everything open, newest first", "category != done ORDER BY updated DESC");
    }

    /** The suggestions of the field: a name, the query so far and the word being typed. */
    static final List<String[]> SUGGESTIONS = List.of(
            new String[] {"Fields", "", "st"},
            new String[] {"Values of labels", "labels = ", "pa"},
            new String[] {"Values of status", "status = ", "in"},
            new String[] {"Values of sprint", "sprint = ", "P04"});

    record Measure(String name, String query, long p50, long p95, long max, Object total) {
    }

    @Test
    void p95OfTheTypicalQueriesAndSuggestions() throws Exception {
        List<Measure> measures = new ArrayList<>();
        for (Map.Entry<String, String> q : QUERIES.entrySet()) {
            String body = "{\"query\":" + json(q.getValue()) + ",\"page\":1,\"size\":50}";
            Object[] total = new Object[1];
            measures.add(measure(q.getKey(), q.getValue(), () -> {
                String answer = mvc.perform(as(post("/api/search")).content(body)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                total[0] = com.jayway.jsonpath.JsonPath.read(answer, "$.total");
            }, total));
        }
        for (String[] s : SUGGESTIONS) {
            measures.add(measure("Suggestions: " + s[0], s[1] + s[2], () -> mvc.perform(as(get("/api/search/words"))
                    .param("q", s[1] + s[2]).param("word", s[2])).andExpect(status().isOk()), null));
        }
        String report = report(measures);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "search-load.md"), report);
        System.out.println(report);
        assertThat(measures).allSatisfy(m -> assertThat(m.p95()).as(m.name()).isLessThan(TARGET_MS));
    }

    interface Call {
        void run() throws Exception;
    }

    private static Measure measure(String name, String query, Call call, Object[] total) throws Exception {
        for (int i = 0; i < WARM_UP; i++) {
            call.run();
        }
        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            call.run();
            times[i] = (System.nanoTime() - start) / 1_000_000;
        }
        java.util.Arrays.sort(times);
        return new Measure(name, query, times[RUNS / 2], times[(int) Math.ceil(RUNS * 0.95) - 1], times[RUNS - 1],
                total == null ? null : total[0]);
    }

    private static String report(List<Measure> measures) {
        StringBuilder md = new StringBuilder("| Query | ODQL | Found | p50 ms | p95 ms | max ms |\n| --- | --- | --- | --- | --- | --- |\n");
        for (Measure m : measures) {
            md.append("| ").append(m.name()).append(" | `").append(m.query()).append("` | ")
                    .append(m.total() == null ? "" : m.total()).append(" | ").append(m.p50()).append(" | ")
                    .append(m.p95()).append(" | ").append(m.max()).append(" |\n");
        }
        return md.toString();
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
