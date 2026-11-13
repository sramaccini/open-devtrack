# HANDOFF — OpenDevTrack

Notes for whoever continues the work. Read [README.md](README.md), [AGENTS.md](AGENTS.md) and [docs/](docs/README.md)
first. The project plan (Italian) is the source of the requirements; parts of it are older than the work done since in
Quadrava and authz-library, and the ADRs say where.

## Status

**7 November 2026 — phases F1, F2 and F3 done; F4 under way: search (ADR-16), dashboards and reports (ADR-17) built.**

- Repository github.com/sramaccini/open-devtrack, branch `main`: licence MPL-2.0 with CLA, contribution rules and
  project rules as Quadrava (ADR-05). Decisions D1–D9 and ADR-10 accepted, in `docs/`.
- `backend/`: the monolith on authz-library 2.2.0, split into the Spring Modulith modules of the plan
  (`ModularityTests`: `verify()` and the module documentation). The `odt.*` permission catalogue is a repeatable
  Flyway migration of the RBAC schema (ADR-07); the dev profile adds tenants, users and the UI author/approver roles.
- Module `uiregistry` (ADR-03): `contracts/registry.yaml` 0.7.0, adapted from the Quadrava reference registry
  (commit 9fa1916, contract 0.6.0), on PostgreSQL (`odt_uiregistry`), validated with the schemas of Quadrava 0.13.1 in the classpath.
  The browser uses the session; tools use Keycloak access tokens of `opendevtrack-tools` on `/registry/**`
  (the bearer access of authz-library, A4).
- `ui/`: the Quadrava project, with the main navigation, home and projects placeholder screens, themes `odt-light` and
  `odt-dark` from `brand/`, English and Italian catalogues. The Quadrava CLI 0.13.1 checks it (`quadrava validate`) and publishes it
  (`quadrava publish`) to any environment: no environment is protected (ADR-12).
- End-to-end check (`docs/check-authz-end-to-end.md`): Quadrava kits bff 13/13 with and without a session, authz 11/11,
  registry 24/24 (`--protected-env none`), tenant switch kept across logins, `ui/` served by the runtime 0.13.1 from the
  registry. Quadrava 0.5.0 adopted on 4 November 2026 (from 0.2.1: same schemas, registry contract 0.7), 0.6.0 on 5
  November (tab title and icons from the theme, shell texts built in: Q3 and Q4), 0.7.0 on 5 November (theme and
  language in the settings dialog of the user menu; the message bell of ADR-22), 0.8.0 on 6 November (`data.<name>` in the expressions: Q9), 0.9.0 on 6 November (links from the rows, Q10;
  request size of the runtime image, Q11), 0.10.0 on 6 November (comment actions, Q8; tags field, Q5), 0.11.0 on 6 November (the side menu runs the full height of the page; «lowcode» renamed «lc», which OpenDevTrack did not use), 0.11.1 on 7 November (Q12 on the kanban; the collapse button of the side menu back on its edge), 0.12.0 on 7 November (application sections in the settings dialog, Q13; chart axis labels that fit, Q14; the CLI checks the navigation as the runtime does), 0.12.1 on 7 November (an address that opens the settings dialog on a section, Q15), 0.13.0 on 8 November (field widths and the flow layout of forms, used by every form of `ui/`), 0.13.1 on 12 November (a value of a select outside its options shown as it is: a status of a workflow in `issue-detail`).
- CI (`.github/workflows/ci.yml`), green on its first run: `quadrava validate` of `ui/` and the back end tests, then the e2e with the
  kits. It reads authz-library and the Quadrava CLI from GitHub Packages with the secret `AUTHZ_PACKAGES_TOKEN` (or the workflow token if the packages grant this repository access).
- F2 E1 Projects: modules `project` (projects of the tenant, issue numbering), `access` (members and project roles
  viewer/developer/admin, always one admin) and `identity` (users of the tenant for pickers); screens
  `project-list`, `project-new`, `project-detail` with members. Project permissions reach the screens as `_perm`
  until A1 (ADR-07). `ProjectApiTest` covers two tenants and users without the permissions.
- F2 E2 Issues (first slice): module `issue` with type, priority, summary, Markdown description, assignee, labels and
  numbering per project; screens `issue-list`, `issue-new`, `issue-detail`. Labels as a `tags` field with the labels of the
  project as suggestions (Quadrava 0.10.0, Q5);
  the status changes through the workflow (E3). Links between issues (`blocks`, `relates`, `duplicates`, read from
  both ends, also across projects of the tenant) with a Links tab on `issue-detail`. Watchers: the reporter and the
  assignee from the start, anyone who sees the issue watches it or stops, developers add members; a Watchers tab. Who
  is told of what comes with the module `notification`. Custom fields (ADR-11, accepted 1 November 2026, on
  `fieldsFrom` of Quadrava 0.2.1): seven types, defined on `project-detail`, set in `issue-new` and `issue-detail`,
  `required_if` in CEL checked by the back end. In the history a custom field shows its raw name (`custom.customer`).
- F2 E3 Workflow (ADR-10, accepted 1 November 2026): module `workflow`, one workflow per project copied from the
  default, CEL guards with cel-java 0.14.0, transitions listed and run on `issue-detail` («Next steps»), conditions
  edited on `project-detail`. With it gate M1 is covered: create, edit, transition and comment an issue, with
  permissions and changelog. The admin of the tenant (`odt.project.admin`, role `ODT_ADMIN`) is admin of every
  project.
- F2 E4: comments (module `collaboration`) and the changelog of the issues (module `audit`), both from
  the events of `issue` after the commit (Event Publication Registry of Spring Modulith on PostgreSQL); tabs Comments
  and History on `issue-detail`. The values in the history are shown as stored (`task`, `medium`), not translated.
  Mentions and messages (ADR-13, accepted 5 November 2026): `@username` of a member in a comment, picked in the
  `comments` thread of Quadrava; messages for mentions, assignments, comments and status changes in the bell of the
  runtime (module `notification`, polled every 60 s, kept 90 days). Their language follows `Accept-Language`. The box
  for writing shows when `data.issue._perm.comment` (Q9, Quadrava 0.8.0); editing and deleting a comment
  happen in the thread (Q8, Quadrava 0.10.0).
  Attachments (ADR-14, accepted 6 November 2026): one `AttachmentStore` with the plugins `database` (default),
  `filesystem` and `s3` (AWS SDK v2, any S3-compatible server; tests on S3Mock), metadata in PostgreSQL, bytes
  always through the back end, an Attachments tab on `issue-detail` that downloads from the name of a file (Q10)
  and uploads up to 25 MB through the runtime image (Q11, Quadrava 0.9.0).
- F3 (ADR-15, accepted 6 November 2026): module `agile` with one order per project (fractional indexes in base 62),
  sprints (planned, one active, completed, with what each held for the reports of F4), story points, and boards whose
  columns are statuses of the workflow, with WIP limits and lanes by assignee, priority or type. A move to another
  column runs a transition, guards included. Screens `board`, `backlog` and `board-list` on the `kanban` of Quadrava
  0.10.0; boards on `project-detail`, points on `issue-detail`. Q12 adopted with Quadrava 0.11.1: a card opens its
  issue, translated badges, one board component for every kind of lanes.
- F4 E7 (ADR-16, accepted 7 November 2026): module `search` with ODQL (hand-written parser, compiled to jOOQ with
  bound values) on the read model `odt_search.issue_doc`, rebuilt after the commit from the events of `issue` and
  `agile`; full text with `unaccent` and a `simple` `tsvector`. Screen `search` with suggestions as one types,
  results in a `serverDataTable` (filters and groups by the back end) and saved filters, private or shared. The filter
  of a board (point 7) goes through `shared.IssueQueries`, implemented by `search`, so `agile` does not depend on
  `search` (which depends on `agile`). The load test of point 8 (`SearchLoadTest`, tag `load`, outside CI) measures
  every p95 under 0.6 s on one million issues ([docs/search-load.md](docs/search-load.md)).
- F4 E8 (ADR-17, accepted 7 November 2026): module `reporting` with the read model `odt_reporting` (issue rows with
  their days, daily snapshots per project and active sprint, sprint results), written after the commit and each night;
  reports of a project (indicators, burndown, velocity, cumulative flow, created vs resolved) and the personal counts
  from the search. Screens `dashboard` (the first screen, in place of `home`) and `project-dashboard`. The axis of the
  charts leaves out the day labels that do not fit (Q14, Quadrava 0.12.0). The sprint of the burndown is chosen on
  the screen (a lookup of `…/reports/sprints?q=`, the active or the last one when empty). «Overdue» and «In review
  by me» come with ADR-19 (below).
- F4 E9 (ADR-18, accepted 7 November 2026): preferences per user and kind (in the application; email off, at once or
  in the daily digest), an outbox in `odt_notification.message` sent by `MailOutbox` over SMTP (Spring Mail; retries,
  `SKIP LOCKED`), plain-text mails in the last language the user used. Screen `notification-settings` as the
  «Notifications» section of the settings dialog (Q13, Quadrava 0.12.0), which the footer of the mails opens with
  `/?settings=notification-settings` (Q15, Quadrava 0.12.1). The e2e environment has Mailpit (mails at http://localhost:28025) and checks that a
  mention is mailed. The address is read from the directory of authz-library without a session (A5).
- F4, ADR-19 (accepted 7 November 2026): an issue has an optional due date (`dueOn`) and reviewer (a member, not the
  assignee, who then watches it); ODQL `due` and `reviewer`; «Overdue» and «In review by me» on `dashboard`; the
  badge «Overdue» in `issue-list` and `search`; the message `review_requested` with its preference.
- ADR-20 (accepted 8 November 2026): an admin of the project edits statuses and transitions (A; removing a status moves
  its issues), and a project has several workflows mapped by issue type, a default one for the rest (B; an issue that
  changes workflow keeps its status or moves to the first one of the same category). Screens: the Workflows table of
  `project-detail`, `workflow-detail`.
- F5, ADR-21 (accepted 12 November 2026): the public API on `/api` with Keycloak tokens, service accounts and the
  OpenAPI definition (A), and outgoing webhooks per project, signed, retried and turned off after three days of
  failures, with a message to the admins (B; module `integration`).

## Requests to the dependencies

In `docs/README.md`: Q2 (Quadrava 0.2.0 with its CLI), Q5 (a tags field), A1 (extension point for project permissions; `_perm` meanwhile), A2 (schema of the RBAC tables), A3 (Problem
texts in English), A4 (bearer access for tools), A5 (read-only list of the tenant users). Each is built in its own repository.

## Environment of this machine

- JDK 25 at `C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`, not in the `PATH` (`java` is 21):
  `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot"`.
- `TEMP` in short 8.3 form: `export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\tmp\ud'` before `mvn`, and
  `E2E_JAVA_OPTS='-Djdk.net.unixdomain.tmpdir=C:\tmp\ud'` for `e2e/authz/run.sh up`. A problem of the machine: not in
  the poms.
- Node through fnm: `eval "$(fnm env --shell bash)"; fnm use 24.21.0`. No `gh`, no Python. The token for
  `npm.pkg.github.com` (Quadrava CLI) is in `~/.npmrc`; the `.npmrc` of the repository only maps `@sramaccini`.
- Docker is needed by the back end tests (Testcontainers) and by the e2e. The e2e login is limited to 20 per minute per
  IP by authz-library: wait a minute between two `check` runs or a check and a browser login.
- No `~/.m2/settings.xml`: the authz-library artifacts resolve from the local Maven repository, where the
  authz-library build installed them. A clean machine or CI needs the token (backend/README.md).
- Ports 8180 and 8090 are used by containers of other projects: the e2e environment uses 25432, 26379, 28080, 28090,
  28180.
- `stack/stack.sh up` (stack/README.md): everything in Docker for trying and reviewing, with sample projects, on
  18080, 18090, 18180, 18025; the back end image is built from `backend/` with the npm token of `~/.npmrc` as
  the Maven credentials.
- Commits only as `Simone Ramaccini <s.ramaccini@gmail.com>` (repository `git config`; the global one has another
  address), no trailers or tool attributions anywhere.

## Next steps

1. F5: ADR-21 (public API and webhooks) built. A: `/api` with Keycloak tokens and service accounts (the bearer
   access of authz-library 2.2.0, A4), the OpenAPI definition `docs/openapi.json` and `docs/api.md`. B: outgoing
   webhooks (module `integration`), signed as Standard Webhooks, from an outbox, with the screens `webhook-list` and
   `webhook-detail` (a screen of their own, not a section of `project-detail`: `ui/README.md`) and the receiver `echo`
   of the stack. A1, A2 and A5 of authz-library 2.2.0 adopted. ADR-22 (administration, E10) and ADR-23
   (hardening: ASVS L2, WCAG 2.2 AA) accepted 12 November 2026. ADR-22: the Administration area (users, roles,
   tenants, access log over the admin API of authz-library) the permission schemes (module `access`, the screen
   `admin-permission-schemes`, the choice in `project-detail`) and the groups (`admin-groups`, the groups of a
   project in `project-detail`) and the audit log of the configuration (`odt_audit.admin_event`, `admin-config-log`,
   `project-log`) built: ADR-22 is done; next ADR-23. Q16 and Q17 for Quadrava are written in `docs/README.md` and not sent yet; then the documentation, the public demo
   and 1.0.
2. Deployment: publish `ui/` to `testing`/`production` with `quadrava publish` and the token of an author.
