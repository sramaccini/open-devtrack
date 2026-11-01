# HANDOFF — OpenDevTrack

Notes for whoever continues the work. Read [README.md](README.md), [AGENTS.md](AGENTS.md) and [docs/](docs/README.md)
first. The project plan (Italian) is the source of the requirements; parts of it are older than the work done since in
Quadrava and authz-library, and the ADRs say where.

## Status

**1 November 2026 — phase F1 done; F2 with E1 Projects, E2 Issues, E3 Workflow and E4 Collaboration (first slices): gate M1 covered.**

- Repository github.com/sramaccini/open-devtrack, branch `main`: licence MPL-2.0 with CLA, contribution rules and
  project rules as Quadrava (ADR-05). Decisions D1–D9 and ADR-10 accepted, in `docs/`.
- `backend/`: the monolith on authz-library 2.0.1, split into the Spring Modulith modules of the plan
  (`ModularityTests`: `verify()` and the module documentation). The `odt.*` permission catalogue is a repeatable
  Flyway migration of the RBAC schema (ADR-07); the dev profile adds tenants, users and the UI author/approver roles.
- Module `uiregistry` (ADR-03): `contracts/registry.yaml` 0.6.0 adapted from the Quadrava reference registry
  (commit 9fa1916), on PostgreSQL (`odt_uiregistry`), validated with the schemas of Quadrava 0.2.1 in the classpath.
  The browser uses the session; tools use Keycloak access tokens of `opendevtrack-tools` on `/registry/**`
  (`platform/BearerTokenSecurity.java`, temporary until A4).
- `ui/`: the Quadrava project, with the main navigation, home and projects placeholder screens, themes `odt-light` and
  `odt-dark` from `brand/`, English and Italian catalogues. `tools/publish-ui.mjs` publishes it (until Q2).
- End-to-end check (`docs/check-authz-end-to-end.md`): Quadrava kits bff 13/13 with and without a session, authz 11/11,
  registry 23/23, tenant switch kept across logins, `ui/` served by the runtime 0.2.1 from the registry.
- CI (`.github/workflows/ci.yml`), green on its first run: back end tests, then the e2e with the kits. It reads authz-library from GitHub
  Packages with the secret `AUTHZ_PACKAGES_TOKEN` (or the workflow token if the packages grant this repository access).
- F2 E1 Projects: modules `project` (projects of the tenant, issue numbering), `access` (members and project roles
  viewer/developer/admin, always one admin) and `identity` (users of the tenant for pickers); screens
  `project-list`, `project-new`, `project-detail` with members. Project permissions reach the screens as `_perm`
  until A1 (ADR-07). `ProjectApiTest` covers two tenants and users without the permissions.
- F2 E2 Issues (first slice): module `issue` with type, priority, summary, Markdown description, assignee, labels and
  numbering per project; screens `issue-list`, `issue-new`, `issue-detail`. Labels are in the API only (request Q5);
  the status changes through the workflow (E3). Links between issues (`blocks`, `relates`, `duplicates`, read from
  both ends, also across projects of the tenant) with a Links tab on `issue-detail`. Watchers: the reporter and the
  assignee from the start, anyone who sees the issue watches it or stops, developers add members; a Watchers tab. Who
  is told of what comes with the module `notification`.
- F2 E3 Workflow (ADR-10, accepted 1 November 2026): module `workflow`, one workflow per project copied from the
  default, CEL guards with cel-java 0.14.0, transitions listed and run on `issue-detail` («Next steps»), conditions
  edited on `project-detail`. With it gate M1 is covered: create, edit, transition and comment an issue, with
  permissions and changelog. The admin of the tenant (`odt.project.admin`, role `ODT_ADMIN`) is admin of every
  project.
- F2 E4 (first slice): comments (module `collaboration`) and the changelog of the issues (module `audit`), both from
  the events of `issue` after the commit (Event Publication Registry of Spring Modulith on PostgreSQL); tabs Comments
  and History on `issue-detail`. The values in the history are shown as stored (`task`, `medium`), not translated.

## Requests to the dependencies

In `docs/README.md`: Q2 (Quadrava 0.2.0 with its CLI), Q3 (favicon and title of the runtime image), Q4 (shell texts of
the runtime built in), Q5 (a tags field), A1 (extension point for project permissions; `_perm` meanwhile), A2 (schema of the RBAC tables), A3 (Problem
texts in English), A4 (bearer access for tools), A5 (read-only list of the tenant users). Each is built in its own repository.

## Environment of this machine

- JDK 25 at `C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`, not in the `PATH` (`java` is 21):
  `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot"`.
- `TEMP` in short 8.3 form: `export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\tmp\ud'` before `mvn`, and
  `E2E_JAVA_OPTS='-Djdk.net.unixdomain.tmpdir=C:\tmp\ud'` for `e2e/authz/run.sh up`. A problem of the machine: not in
  the poms.
- Node through fnm: `eval "$(fnm env --shell bash)"; fnm use 24.21.0`. No `gh`, no Python.
- Docker is needed by the back end tests (Testcontainers) and by the e2e. The e2e login is limited to 20 per minute per
  IP by authz-library: wait a minute between two `check` runs or a check and a browser login.
- No `~/.m2/settings.xml`: the authz-library 2.0.1 artifacts resolve from the local Maven repository, where the
  authz-library build installed them. A clean machine or CI needs the token (backend/README.md).
- Ports 8180 and 8090 are used by containers of other projects: the e2e environment uses 25432, 26379, 28080, 28090,
  28180.
- Commits only as `Simone Ramaccini <s.ramaccini@gmail.com>` (repository `git config`; the global one has another
  address), no trailers or tool attributions anywhere.

## Next steps

1. F2 (per the plan): the rest of E4 (mentions; attachments after an ADR on where the files are kept); workflows by
   issue type and editing statuses and transitions when a project needs them (ADR-10).
2. Custom fields (E2) are on hold until Quadrava implements Q6 (`fieldsFrom`, form fields from a data source),
   decided on 1 November 2026: no temporary Fields tab. ADR-11 stays proposed; when Q6 is released it is revised so
   the fields are in the forms of `issue-new` and `issue-detail`, then accepted and built.
3. Adopt the CLI of Quadrava 0.2.1 (`@sramaccini/quadrava-cli` on GitHub Packages, request Q2 released):
   `quadrava validate` and `publish` in CI instead of `tools/publish-ui.mjs`. The runtime image, the schemas and the
   kits are already at 0.2.1.
4. Deployment: publish `ui/` to `testing`/`production` with an author and an approver token.
