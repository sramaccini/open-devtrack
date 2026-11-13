# Load test of the search (ADR-16, gate M3)

ADR-16 point 8 asks for p95 < 1 s of the typical ODQL queries on one million issues. `SearchLoadTest` measures it
through the real API (`POST /api/search`, `GET /api/search/words`) on PostgreSQL 18 with the migrations of the
application, the indexes of `odt_search` as they are.

## How it runs

The test carries the JUnit tag `load` and is left out of the normal build (`<excludedGroups>load</excludedGroups>` in
`backend/pom.xml`, so CI does not run it). On request, with Docker:

```bash
mvn -f backend/pom.xml test -Dgroups=load -DexcludedGroups=none -Dtest=SearchLoadTest -Dsurefire.failIfNoSpecifiedTests=false
```

It writes its table to `backend/target/search-load.md` and fails if a p95 is over 1000 ms.

## Data

Written with `generate_series` straight into `odt_search.issue_doc` (the read model the queries read), then `ANALYZE`:

- one tenant, 200 projects (`P001`–`P200`), the user of the test a developer of each, 3 sprints per project (one
  active);
- 1,000,000 issues, 5,000 per project: 4 types, statuses in the three categories (about 33% to do, 14% in progress,
  the rest done), 5 priorities, 500 assignees and 10% unassigned, 300 reporters, labels from 40, summaries and
  descriptions from a vocabulary of 60 words, created and updated over two years, resolved when done, one open issue
  in five in a sprint;
- the `tsvector` computed by `odt_search.text_vector`, as the listeners do.

Writing the rows takes about 50 s; the whole test about 105 s.

## Measure

Each query runs 3 times to warm up, then 20 times; page 1 of 50 rows, as the `search` screen asks. The times are of the
whole request through MockMvc (security, compile, count and page).

## Results

Measured on 7 November 2026: Intel Core i7-1255U (10 cores, 12 threads), 32 GB, Windows 11; Docker 29.9.1 with 12 CPUs
and about 16 GB; `postgres:18` from Testcontainers with its default settings.

| Query | ODQL | Found | p50 ms | p95 ms | max ms |
| --- | --- | ---: | ---: | ---: | ---: |
| My open work | `assignee = currentUser() AND category != done ORDER BY priority DESC` | 775 | 30 | 129 | 140 |
| One project, in progress | `project = P007 AND status = in_progress` | 476 | 157 | 200 | 202 |
| Sprint of a project | `project = P042 AND sprint IN openSprints() ORDER BY rank` | 429 | 144 | 156 | 157 |
| Urgent open bugs | `type = bug AND priority >= high AND category != done` | 42,857 | 117 | 159 | 165 |
| Full text | `text ~ "payment timeout"` | 3,329 | 44 | 131 | 134 |
| Summary contains | `summary ~ checkout` | 50,474 | 340 | 350 | 360 |
| Label, updated this week | `labels = backend AND updated >= -7d` | 3,163 | 41 | 131 | 137 |
| Created this month | `created >= -30d ORDER BY created DESC` | 42,119 | 33 | 129 | 129 |
| Reported by me, resolved lately | `reporter = currentUser() AND resolved >= -14d` | 145 | 15 | 109 | 110 |
| Everything open, newest first | `category != done ORDER BY updated DESC` | 428,572 | 187 | 246 | 248 |

| Suggestions | Typed | p50 ms | p95 ms | max ms |
| --- | --- | ---: | ---: | ---: |
| Fields | `st` | 1 | 1 | 1 |
| Values of labels | `labels = pa` | 346 | 504 | 508 |
| Values of status | `status = in` | 88 | 106 | 152 |
| Values of sprint | `sprint = P04` | 8 | 15 | 20 |

## Reading

- Every p95 is under the target, the slowest query at 350 ms (`summary ~`, a trigram match on 50,000 rows): no index
  is added and OpenSearch is not needed (ADR-16, alternatives).
- The suggestions of the values of `labels` are the closest to the target (about 0.3–0.5 s between runs): they unnest
  the labels of every issue of the visible projects. If they grow slower with more labels or projects, the first step
  is a table of the labels per project kept by the listeners.
- The suggestions of `status` read the workflow of each visible project, one query each (200 here); fine at this size,
  to be batched if a tenant has many more projects.
- The data is spread evenly; a real tenant with a few large projects would make `project = …` queries slower and the
  queries of the whole tenant no slower. The test is the place to try such shapes before changing an index.

## Row-level security (ADR-23)

ADR-23 puts row-level security of PostgreSQL on the tables with a `tenant_id` only if this test stays under p95 <
0.6 s with it. Tried on 13 November 2026, same machine, with what row-level security needs:

- a policy `tenant_id = current_setting('odt.tenant')` (or `'*'` for the system) on the 11 tables, `FORCE`d;
- the back end connected as an ordinary role owning its database (a superuser is never bound);
- `odt.tenant` set on each connection taken from the pool.

| Query | p95 ms without | p95 ms with |
| --- | ---: | ---: |
| My open work | 129 | 177 |
| One project, in progress | 200 | 280 |
| Sprint of a project | 156 | 165 |
| Urgent open bugs | 159 | 204 |
| Full text | 131 | 284 |
| **Summary contains** | 350 | **2,358** |
| Label, updated this week | 131 | 247 |
| Created this month | 129 | 146 |
| Reported by me, resolved lately | 109 | 125 |
| Everything open, newest first | 246 | 312 |
| Suggestions: values of labels | 504 | 332 |
| Suggestions: values of status | 106 | 190 |

`summary ~` is far over the limit, and the full text doubles. Under a policy, PostgreSQL does not use an operator
that is not `LEAKPROOF` as an index condition before the policy. The trigram match (`ILIKE`, `%`) and the full text
(`@@`) are not leakproof, so their GIN indexes stop serving and the match runs on every row of the tenant. Marking
them leakproof needs a superuser and would not be true of them.

So row-level security is left for 1.x, as ADR-23 says. Ways to look at then:

- policies only on the tables that the search does not read with such operators, `odt_search.issue_doc` keeping the
  checks of ADR-09 alone;
- or the search on a role or a view of its own.

Either way, the role of the back end must not be a superuser: that is written in the requirements of
`docs/security/asvs.md` for the installation.

The test itself wrote to `tenant` without a schema, which broke when the RBAC tables moved to the schema `authz`
(request A2): fixed with this run.
