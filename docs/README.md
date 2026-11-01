# Documentation

## Architecture decision records

The decisions D1–D9 of the project plan, all accepted by the maintainer on 31 October 2026, before phase F1.

| ADR | Decision | In short | Status |
| --- | --- | --- | --- |
| [01](adr-01-quadrava-distribution.md) | How OpenDevTrack gets Quadrava (D1) | The runtime image of a pinned version, the CLI in CI | Accepted |
| [02](adr-02-java-version.md) | Java version (D2) | Java 25 LTS, required by authz-library 2.0.1 | Accepted |
| [03](adr-03-screen-definitions-in-production.md) | Where the screens come from in production (D3) | A registry module in the monolith, started from the Quadrava reference | Accepted |
| [04](adr-04-persistence.md) | Persistence for search and reports (D4) | JPA for commands, jOOQ for search and reports | Accepted |
| [05](adr-05-licence.md) | Licence (D5) | MPL-2.0 with CLA, as Quadrava | Accepted |
| [06](adr-06-board-columns-from-data.md) | Board columns from data (D6) | `columnsFrom` of the Quadrava `kanban`, one generic board screen | Accepted |
| [07](adr-07-permission-format.md) | Permission format (D7) | `odt.<resource>.<action>` everywhere, catalogue by a migration script; authz-library A1 for project permissions | Accepted |
| [08](adr-08-tenant-selection.md) | Choosing the tenant (D8) | Optional selector of the Quadrava shell, last tenant at login | Accepted |
| [09](adr-09-multi-tenant-data.md) | Multi-tenant data (D9) | `tenant_id` on the project, filtered from the session | Accepted |
| [10](adr-10-workflows.md) | Workflows and their CEL guards (E3) | Workflow per project from a built-in default, guards in CEL with cel-java, transitions allowed as an API | Accepted |
| [11](adr-11-custom-fields.md) | Custom fields of the issues (E2) | JSONB values checked against definitions per project, `required_if` in CEL, in the forms with `fieldsFrom` of Quadrava 0.2.1 | Accepted |

## Requests to the dependencies

Found while writing the ADRs; each is built in its own repository, optional for the other users.

| # | Where | What | Needed by |
| --- | --- | --- | --- |
| Q2 | Quadrava | Release 0.2.0 with the CLI `quadrava validate` and `publish` (ADR-01, ADR-03) | Start of F1 |
| Q3 | Quadrava | Favicon and page title of the runtime image set by the application (today the icons of the image are mounted over, and the tab reads «Quadrava») | F5 |
| Q4 | Quadrava | Shell texts of the runtime (`app.theme`, `app.language`, errors) built in, as with the examples, instead of required in the catalogue of every project | F5 |
| Q5 | Quadrava | A field for a free list of short texts (tags), for the labels of the issues: `multiselect` only takes fixed `options` (the API has the labels, the screens do not show them yet) | F2 |
| Q6 | Quadrava | Form fields from a data source (`fieldsFrom`, like `columnsFrom` of the `kanban`), for the custom fields of a project in `issue-new` and `issue-detail` (ADR-11; released in 0.2.0, adopted with 0.2.1) | F2 |
| A1 | authz-library | Extension point for permissions that depend on a resource (ADR-07; until then `_perm` on rows and forms) | F2 |
| A2 | authz-library | A configurable schema for the RBAC tables (today they go into `public`, next to the `odt_*` schemas of the modules) | F1 |
| A3 | authz-library | Problem titles and details in English (today Italian, for example «Troppi tentativi di accesso») | F5 |
| A4 | authz-library | Bearer access for tools: a Keycloak access token resolved to the same permissions as a session, on chosen paths (today `platform/BearerTokenSecurity.java`, ADR-03) | F5 |
| A5 | authz-library | A read-only list of the users of the session's tenant, for pickers, behind a permission of the application (today `identity/TenantUsers.java` reads `RbacAdminService.tenantUsers` with an admin scope of its own) | F2 |

## Checks

- [End-to-end check of authz-library](check-authz-end-to-end.md) (F0).
