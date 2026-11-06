# HANDOFF — OpenDevTrack

Notes for whoever continues the work. Read [README.md](README.md), [AGENTS.md](AGENTS.md) and [docs/](docs/README.md)
first. The project plan (Italian) is the source of the requirements; parts of it are older than the work done since in
Quadrava and authz-library, and the ADRs say where.

## Status

**6 November 2026 — phase F1 done; F2 with E1 Projects, E2 Issues, E3 Workflow and E4 Collaboration (comments, mentions, messages, attachments): gate M1 covered.**

- Repository github.com/sramaccini/open-devtrack, branch `main`: licence MPL-2.0 with CLA, contribution rules and
  project rules as Quadrava (ADR-05). Decisions D1–D9 and ADR-10 accepted, in `docs/`.
- `backend/`: the monolith on authz-library 2.0.1, split into the Spring Modulith modules of the plan
  (`ModularityTests`: `verify()` and the module documentation). The `odt.*` permission catalogue is a repeatable
  Flyway migration of the RBAC schema (ADR-07); the dev profile adds tenants, users and the UI author/approver roles.
- Module `uiregistry` (ADR-03): `contracts/registry.yaml` 0.7.0, adapted from the Quadrava reference registry
  (commit 9fa1916, contract 0.6.0), on PostgreSQL (`odt_uiregistry`), validated with the schemas of Quadrava 0.10.0 in the classpath.
  The browser uses the session; tools use Keycloak access tokens of `opendevtrack-tools` on `/registry/**`
  (`platform/BearerTokenSecurity.java`, temporary until A4).
- `ui/`: the Quadrava project, with the main navigation, home and projects placeholder screens, themes `odt-light` and
  `odt-dark` from `brand/`, English and Italian catalogues. The Quadrava CLI 0.10.0 checks it (`quadrava validate`) and publishes it
  (`quadrava publish`) to any environment: no environment is protected (ADR-12).
- End-to-end check (`docs/check-authz-end-to-end.md`): Quadrava kits bff 13/13 with and without a session, authz 11/11,
  registry 24/24 (`--protected-env none`), tenant switch kept across logins, `ui/` served by the runtime 0.10.0 from the
  registry. Quadrava 0.5.0 adopted on 4 November 2026 (from 0.2.1: same schemas, registry contract 0.7), 0.6.0 on 5
  November (tab title and icons from the theme, shell texts built in: Q3 and Q4), 0.7.0 on 5 November (theme and
  language in the settings dialog of the user menu; the message bell of ADR-22), 0.8.0 on 6 November (`data.<name>` in the expressions: Q9), 0.9.0 on 6 November (links from the rows, Q10;
  request size of the runtime image, Q11), 0.10.0 on 6 November (comment actions, Q8; tags field, Q5).
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
- No `~/.m2/settings.xml`: the authz-library 2.0.1 artifacts resolve from the local Maven repository, where the
  authz-library build installed them. A clean machine or CI needs the token (backend/README.md).
- Ports 8180 and 8090 are used by containers of other projects: the e2e environment uses 25432, 26379, 28080, 28090,
  28180.
- Commits only as `Simone Ramaccini <s.ramaccini@gmail.com>` (repository `git config`; the global one has another
  address), no trailers or tool attributions anywhere.

## Next steps

1. F2 (per the plan): workflows by
   issue type and editing statuses and transitions when a project needs them (ADR-10).
2. Deployment: publish `ui/` to `testing`/`production` with `quadrava publish` and the token of an author.
