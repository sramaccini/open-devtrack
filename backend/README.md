# OpenDevTrack back end

The monolith: Spring Boot 4.1, Java 25 (ADR-02), with login, session and global roles from the authz-library libraries
`com.logicwarez.authz:authz-bff` and `authz-rbac` (which bring `authz-session`), split into Spring Modulith modules
(`ModularityTests` checks their boundaries). The first with code is `uiregistry`, the registry of the screen definitions
(ADR-03).

## authz-library from GitHub Packages

The libraries are published on GitHub Packages (`https://maven.pkg.github.com/sramaccini/authz-library`), which asks for
a token also to read. Create a personal access token with the `read:packages` scope and add it to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>authz-library</id>
      <username>YOUR_GITHUB_USER</username>
      <password>YOUR_TOKEN</password>
    </server>
  </servers>
</settings>
```

The `id` must match the repository declared in `pom.xml`. In CI the same server is written from a secret.

## Build and run

```bash
export JAVA_HOME=<JDK 25>
mvn -f backend/pom.xml package
```

The tests that start the application (`RegistryApiTest`) run PostgreSQL 18 and Redis 8.2 with Testcontainers: they
need Docker.

The application needs PostgreSQL, Redis and Keycloak; the quickest way to have them, with the Quadrava runtime in
front, is `e2e/authz/run.sh up` ([docs/check-authz-end-to-end.md](../docs/check-authz-end-to-end.md)).

Configuration (`application.yml`): `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `IAM_ISSUER_URI`,
`IAM_CLIENT_ID`, `IAM_CLIENT_SECRET`, `FRONTEND_URL` (the address of the runtime), `PORT`. The profile `dev` adds the
development tenants and users and the session cookie over http.

## Registry of the screen definitions (`uiregistry`)

Implements `contracts/registry.yaml` 0.6.0 of Quadrava, adapted from its reference registry. Versions and publications
are in the schema `odt_uiregistry` (Flyway, `db/odt/uiregistry`). Documents are validated against the schemas of the
pinned Quadrava version in `src/main/resources/quadrava/schemas`, refreshed with `tools/sync-quadrava-schemas.sh
<version>`. The browser reaches it with the session; tools (`quadrava publish`, the registry kit) with a Keycloak
access token of a client listed in `odt.security.bearer.clients` (`platform/BearerTokenSecurity.java`).

- `odt.uidefinition.publish` (role `ODT_UI_AUTHOR`): save versions, request a review, publish.
- `odt.uidefinition.approve` (role `ODT_UI_APPROVER`): approve or reject, never a version of one's own.
- `odt.uiregistry.protected-environments` (default `testing,production`): only approved versions are published there.
- Any signed-in user reads what is published.

## Projects and members (`project`, `access`, `identity`)

Projects belong to the tenant of the session (`odt_project`, ADR-09): another tenant gets 404, and the key is unique per
tenant. Each project numbers its issues (`Projects.nextIssueNumber`). The module `access` keeps the members and their
project role (`odt_access`, `ProjectRole`): viewer browses, developer also creates, edits, transitions and comments,
admin also deletes issues, edits the project and manages its members; a project always keeps one admin. The codes are
in the catalogue (ADR-07) and reach the screens as `_perm` until authz-library A1. `identity` lists the users of the
tenant for the pickers (`GET /api/users`, temporary until A5).

- `odt.project.browse`: list and read the projects of the tenant and their members.
- `odt.project.create` (role `ODT_PROJECT_CREATOR`): create one; the creator becomes its lead and admin.
- `odt.user.browse`: search the users of the tenant.
- `odt.project.admin` (role `ODT_ADMIN`): admin of the tenant, with the role admin in every project of the tenant.

## Issues (`issue`)

Issues of a project (`odt_issue`), keyed by the project and their number (`ODT-42`): type, summary, description
(restricted Markdown), priority, assignee (a member), reporter, labels. Only the members of the project see them;
developers create and edit them, admins delete them; edits carry the version read and get 412 if it changed. The status
changes only through the transitions of the workflow of the project (below). `GET /api/projects/{key}/issues` takes `status`,
`assignee` (`me` for the user) and `q` (text of the summary). Each change is published as an event
(`IssueEvents`); other modules find an issue by its key through `Issues`.

## Links (`issue`)

Links between two issues of the tenant (`odt_issue.issue_link`), one row read from both ends: `blocks` /
`blocked_by`, `relates`, `duplicates` / `duplicated_by`. `GET /api/issues/{key}/links` lists them without the
issues of projects the user does not see; `odt.issue.edit` adds one (`POST` with `relation` and `issue`, towards an
issue the user sees; 409 if it is already there) and removes it. `GET …/links/candidates?q=` proposes the issues of
the same project by key, number or text. Each link added or removed is in the changelog of both issues (field
`links`), and the links of a deleted issue go with it.

## Watchers (`issue`)

The users to tell of the changes of an issue (`odt_issue.watcher`), for the module `notification`. The reporter
watches an issue from its creation and each assignee from the assignment; the view of an issue says whether the user
watches it (`watching`). `GET /api/issues/{key}/watchers` lists them; whoever sees the issue watches it (`POST`, no
`username` or `me`) and stops (`DELETE …/watchers/me`); `odt.issue.edit` also adds a member of the project and
removes anyone. A member who leaves the project keeps the row: whoever sends notifications checks that they still see
the issue.

## Workflows (`workflow`)

Each project gets a copy of the built-in workflow when it is created (`odt_workflow`, ADR-10): statuses `todo`,
`in_progress`, `done` and transitions `start`, `finish`, `reopen`. A transition may have a guard in CEL (cel-java),
on `issue` (its fields after the transition, with `status`), `user` (`username`, `roles`) and `can(permission)` on
the project permissions of the user: `issue.assignee != null`, `can('odt.issue.delete')`. A guard is checked when an
admin of the project saves it (`PUT /api/projects/{key}/workflow/transitions/{code}`); one that fails to evaluate is
false. `GET /api/issues/{key}/transitions` lists the transitions that can run now; `POST` with `transition` and
`version` runs one (`odt.issue.transition`): 409 when it does not start from the status or its guard is false.

## Comments and changelog (`collaboration`, `audit`)

Comments in restricted Markdown (`odt_collaboration`): whoever sees the issue reads them, `odt.issue.comment`
(developer) writes them, the author edits them (with the version read) and deletes them, an admin of the project also
deletes those of others. The changelog (`odt_audit`, `GET /api/issues/{key}/history`) has a row per field changed and
one for the creation. Both react to the events of `issue` with `@ApplicationModuleListener`: after the commit, in a
transaction of their own, through the Event Publication Registry in `odt.event_publication` (an event whose listener
fails is republished at the next start). The comments of a deleted issue are removed the same way.
