# ADR-16: Search, ODQL and saved filters

Status: Proposed (7 November 2026) — F4, epic E7

## Context

E7 of the project plan asks for full-text search, structured filters, a query language (ODQL) and saved filters,
shared or not, with a target of p95 < 1 s on one million issues (gate M3). ADR-04 chose jOOQ for search, with SQL
built at run time from ODQL, never concatenated. The module `search` exists but is empty.

Today `issue-list` filters one project by status, assignee and a text (`q`) inside the summary. The data a query needs
is owned by several modules: the issue fields and custom fields by `issue`, the sprint, the points and the rank by
`agile`, the status categories by `workflow`. A module reads another's schema only through its public API (Spring
Modulith).

Quadrava gives what the screens need: a `text` field with `suggest` (a data source asked with the whole text and the
word at the cursor, the service decides the words: catalogue § 2.7, made for a query language), and the
`serverDataTable` (filters, sorting and groups computed by the service, § 2.19).

## Decision

1. **The language.** One expression, then an optional order:

   ```
   project = WEB AND status IN ("To do", in_progress) AND assignee = currentUser() ORDER BY rank
   text ~ "coupon" AND priority >= high AND created >= -14d
   labels = backend AND sprint IN openSprints() AND custom.customer = "ACME"
   ```

   - Fields, a closed list: `project`, `key`, `type`, `status`, `category` (of the status: `todo`, `in_progress`,
     `done`), `priority`, `assignee`, `reporter`, `labels`, `summary`, `text` (summary, description and labels),
     `created`, `updated`, `resolved`, `sprint`, `points`, `rank` (order only), and `custom.<key>` for a
     custom field of ADR-11.
   - Operators: `=`, `!=`, `IN (…)`, `NOT IN (…)`, `<`, `<=`, `>`, `>=` (dates, numbers and the order of priorities),
     `~` and `!~` (text), `IS EMPTY`, `IS NOT EMPTY`; `AND`, `OR`, `NOT` and parentheses; `ORDER BY field [ASC|DESC]`,
     several fields.
   - Values: a word or a quoted text; a date as `2026-11-07` or relative (`-14d`, `-2w`, `today`); the functions
     `currentUser()` and `openSprints()`. Status values match the status id or its name in any language of the
     workflow.
   - A **hand-written parser** (recursive descent): the grammar is small, the errors
     say where and what was expected (`at 23: expected a value after IN`), and it needs no generated code. The plan
     named ANTLR; for this size the generator costs more than the grammar.
2. **The index.** `search` keeps its own read model, fed by the events after the commit (as `audit` does):
   `odt_search.issue_doc (issue_id, project_id, key, number, type, status, category, priority, assignee, reporter,
   labels, summary, custom jsonb, created_at, updated_at, resolved_at, sprint_id, points, rank, tsv)`.
   `tsv` is a generated column on summary, description and labels, configuration `simple` with `unaccent` (issues mix
   Italian and English), with a GIN index; B-tree indexes on the common filters, GIN on `labels` and
   `custom`. The description is indexed, not stored.
   - `issue` already publishes `IssueCreated`, `IssueChanged` and `IssueDeleted` with the fields that change; `agile`
     publishes a new `IssuePlanned (issueId, sprintId, points, rank)` when the plan of an issue changes; `workflow`
     gives the category of a status through its API.
   - The index can lag the commit by a moment (asynchronous listener with the Event Publication Registry, so nothing
     is lost on a restart). A migration fills it from the existing tables, as V15 did; an admin call rebuilds it.
3. **jOOQ, without generated classes.** The few tables of `search` and `reporting` are declared by hand in their
   module (`DSL.table`, typed `DSL.field`), and the ODQL compiler maps each field of the closed list to one of them,
   with bound values only. This changes one point of ADR-04 («jOOQ classes generated from the migrations»): the
   migrations use PostgreSQL features (`uuidv7()`, generated `tsvector`, collations) that the jOOQ DDL parser does not
   read, and a generator on Testcontainers in every build costs more than declaring a dozen columns.
4. **Access.** A query runs only over the projects of the tenant the user can browse (`ProjectAccess`), added by the
   service as an `AND project_id IN (…)` the user cannot remove; `project = X` on a project not visible gives no rows,
   not an error. Results show the same fields as the issue list; the issue itself is opened from its key.
5. **The API.**
   - `POST /api/search` with `{ query, page, size, sort, filters, groupBy, groupPath, distinct }`: the request of the
     `serverDataTable` plus the query; the header filters and the groups are applied on top of the query, on the
     columns shown (project, key, type, status, priority, assignee, updated). `size` at most 100. A wrong query
     answers 400 with a Problem holding `position` and `expected`.
   - `GET /api/search/words?q=&word=`: the suggestions for the `suggest` field (fields, operators, values of the field
     before the cursor: statuses, priorities, users, labels, sprints, projects).
   - Saved filters: `odt_search.filter (id, tenant_id, owner, name, query, shared, on_dashboard, created_at,
     updated_at)` (`on_dashboard` for ADR-17);
     `GET/POST /api/filters`, `PUT/DELETE /api/filters/{id}` (only the owner). A shared filter is seen by every user of
     the tenant; what it finds still depends on what each user may browse.
6. **Screens.** A `search` screen in the navigation (icon `search`): the query field with `suggest`, a button to run
   it, the `serverDataTable` of the results (a row opens `issue-detail`), «Save filter» in a dialog, and the filters
   of the user and the shared ones in a table that loads one into the field. `issue-list` keeps its simple filters
   and gains «Open in search» with `project = <key>`.
7. **Boards with a filter.** A board may carry an optional ODQL filter (`odt_agile.board.filter`, the plan's
   `filter_odql`), applied to its cards through `search` (for example a board of one team: `labels = payments`).
8. **Measured.** A load test, outside the normal build (JUnit tag `load`, Testcontainers), fills one million issues
   over 200 projects with `generate_series` and measures p95 of ten typical queries and of the suggestions. Its
   results go in `docs/` for M3. If a target is missed, indexes first; moving `search` to OpenSearch only if measured
   as necessary (plan, risks).

## Alternatives considered

- **Query the tables of `issue`, `agile` and `workflow` directly.** No lag and no copy, but `search` would depend on
  the schemas of three modules, the boundaries of Spring Modulith would be broken by SQL, and the joins for one
  million issues would be harder to index than one denormalised table.
- **ANTLR grammar.** Proven, as the plan suggested; it adds a build plugin and generated sources for a grammar of a
  page, and its default errors need rewriting to be readable by a user.
- **OpenSearch or Elasticsearch now.** Better ranking and scale, but a second store to run, secure and keep in step; the
  plan keeps it as an escape hatch if PostgreSQL misses the target.
- **Filters only, no language.** The `serverDataTable` header filters cover one table at a time; a query language is
  what saved filters, board filters and dashboards need to share.

## Consequences

- Module `search`: migration `odt_search` (index, saved filters, the `unaccent` extension), listeners, parser and
  compiler, API, the dependency `org.jooq:jooq` (the version managed by Spring Boot).
- `agile` publishes `IssuePlanned`; `workflow` exposes the category of a status; ADR-04 is amended on code generation.
- Unit tests for the parser and the compiler (every operator, every error), API tests with two tenants and users
  without access, the load test outside CI.
- `ui/`: screen `search`, data sources `search`, `words`, `filters`; navigation item; «Open in search» on
  `issue-list`; the filter of a board on `project-detail`.
