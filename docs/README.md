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
| [12](adr-12-no-protected-environments.md) | No protected environments in the registry | Every version publishable everywhere by default; the review is the one of the code; `tools/publish-ui.mjs` removed | Accepted |
| [13](adr-13-mentions-and-messages.md) | Mentions and messages for the user (E4) | `@username` of members found in the text of comments; messages in `notification` for mentions, assignments, comments and status changes of watched issues, behind the bell of Quadrava 0.7.0 | Accepted |
| [14](adr-14-attachment-storage.md) | Attachments and where their files are kept (E4) | One `AttachmentStore` with three plugins: database (default), filesystem, S3-compatible object storage; bytes always through the back end | Accepted |
| [15](adr-15-boards-backlog-sprints.md) | Boards, backlog and sprints (F3) | One rank per project in fractional indexing; sprints planned, active and completed (one active); boards of workflow statuses with WIP limits and swimlanes; the backlog as a `kanban` of sprints | Accepted |
| [16](adr-16-search-odql.md) | Search, ODQL and saved filters (E7) | A small query language parsed by hand and compiled to SQL with jOOQ, over a read model of `search` with full-text; saved and shared filters; measured on one million issues | Accepted |
| [17](adr-17-dashboards-reports.md) | Dashboards and reports (E8) | Read models of `reporting` fed by events and closed each night; burndown, velocity, cumulative flow by status category, created vs resolved, cycle time; a personal and a project dashboard with a fixed layout | Accepted |
| [18](adr-18-email-notifications.md) | Email, preferences and digests (E9) | Preferences per user and kind; an outbox sent by a job, instant or in a daily digest; plain text mails over SMTP, off without a mail host | Accepted |
| [19](adr-19-due-date-and-reviewer.md) | Due date and reviewer of an issue (E8) | Two standard fields `due_on` and `reviewer`, in ODQL as `due` and `reviewer`; «Overdue» and «In review by me» on the dashboard; a message `review_requested` | Accepted |
| [20](adr-20-editing-workflows-and-schemes.md) | Editing workflows, and workflows by issue type (ADR-10) | Statuses and transitions edited by the project admin, removing a status moves its issues; then several workflows per project mapped by issue type, a default for the rest | Accepted |
| [21](adr-21-public-api-and-webhooks.md) | Public REST API and outgoing webhooks (E11) | The `/api` of the screens as the public API, OpenAPI 3.1 by springdoc kept as a contract in `docs/openapi.json`; Keycloak tokens and service accounts (authz-library A4); webhooks per project from an outbox, signed as Standard Webhooks | Accepted |
| [22](adr-22-administration.md) | Administration: users, groups, permission schemes, audit log (E10) | Screens over the admin API of authz-library for users, roles, tenants and sessions; groups and permission schemes for the project roles in `access`; an audit log of the configuration in `audit` | Proposed |
| [23](adr-23-hardening.md) | Hardening for 1.0: ASVS level 2 and WCAG 2.2 AA | A requirement list, OSV-Scanner, CodeQL and a ZAP baseline in CI; webhook secrets encrypted; row-level security if the load test allows; axe-core on the main screens and a manual check per release | Proposed |

## Requests to the dependencies

Found while writing the ADRs; each is built in its own repository, optional for the other users.

| # | Where | What | Needed by |
| --- | --- | --- | --- |
| Q2 | Quadrava | Release 0.2.0 with the CLI `quadrava validate` and `publish` (ADR-01, ADR-03; released, the CLI adopted with 0.2.1) | Start of F1 |
| Q3 | Quadrava | Favicon and page title of the runtime image set by the application (released in 0.6.0: `brand.name` and `brand.icons` of the theme, adopted with 0.6.0) | F5 |
| Q4 | Quadrava | Shell texts of the runtime (`app.theme`, `app.language`, errors) built in, as with the examples, instead of required in the catalogue of every project (released in 0.6.0, adopted with 0.6.0) | F5 |
| Q5 | Quadrava | A field for a free list of short texts (tags), for the labels of the issues: `multiselect` only takes fixed `options` (the API has the labels, the screens do not show them yet) (released in Quadrava 0.10.0 (`tags`) and adopted) | F2 |
| Q6 | Quadrava | Form fields from a data source (`fieldsFrom`, like `columnsFrom` of the `kanban`), for the custom fields of a project in `issue-new` and `issue-detail` (ADR-11; released in 0.2.0, adopted with 0.2.1) | F2 |
| Q7 | Quadrava | `quadrava publish` to a protected environment: request the review as author and approve as reviewer, with two tokens, for projects without Studio (declined: contract 0.7 of Quadrava 0.5.0 makes protected environments optional; ADR-12) | F5 |
| Q8 | Quadrava | Actions on one comment of the `comments` component (edit and delete, with a condition each, as `rowActions` of a table), so the thread replaces the table of comments (ADR-13) (released in Quadrava 0.10.0 (`onEdit`, `onDelete`) and adopted) | F2 |
| Q9 | Quadrava | The data of a screen data source in the expressions outside a form (or `form` in the tabs next to it), so `postIf` of the `comments` thread can test `_perm.comment` of the issue; released in Quadrava 0.8.0 (`data.<name>`, its ADR-23) and adopted (ADR-13) | F2 |
| Q10 | Quadrava | A table column or row action that opens a relative path of the application (as `href` of `link`), to download an attachment (ADR-14; released in Quadrava 0.9.0, `href`, its ADR-24, and adopted) | F2 |
| Q11 | Quadrava | A setting of the runtime image for the size of a request forwarded to the BFF (`client_max_body_size` of nginx, 1 MB today), so the 100 MB of the `file` field reach the back end (ADR-14; released in Quadrava 0.9.0, `QUADRAVA_MAX_BODY_SIZE`, and adopted) | F2 |
| Q12 | Quadrava | On a card of the `kanban`: open a screen with the row (as `onClick` or `screen` and `params`), and a catalogue key for the values of `badges` (as `valueKey` of a column), so a board opens its issues and shows `Bug`, `High` in the language of the user; if it fits, `laneField` and `lanes` as expressions, for one board component instead of four (ADR-15; asked 6 November 2026; released in Quadrava 0.11.1, `onOpen`, `badges[].valueKey`, and adopted) | F3 |
| Q13 | Quadrava | Application items in the user menu (label, icon, a screen or steps, `requires`) and application sections in the settings dialog (a form read from and saved to data sources), for the notification preferences; if it fits, the language chosen in the dialog sent to the back end (ADR-18; asked 7 November 2026; until then a screen in the navigation; released in Quadrava 0.12.0, `settings` in the navigation, and adopted; the address of a section came with Q15) | F4 |
| Q14 | Quadrava | The `chart` leaves out the labels of the categories that do not fit on the axis (first and last kept, an even step between; the full label in the tooltip), so 30 days or a two-week sprint by day can be read (ADR-17; asked 7 November 2026; until then the reports send a short `MM-dd` label and the 30-day charts take the full width; released in Quadrava 0.12.0 and adopted: ISO days with `categoryFormat: "date"`, half width) | F4 |
| Q15 | Quadrava | An address that opens the settings dialog on a section (for example `/?settings=notification-settings`), so a mail can link to the notification settings (ADR-18; asked 7 November 2026; released in Quadrava 0.12.1, `?settings=<section>`, and adopted: the footer of the mails links to `/?settings=notification-settings`) | F4 |
| Q16 | Quadrava | A translated label for an option value that a catalogue key cannot hold, such as `*` (every event of a webhook, ADR-21): for example `optionKeys` mapping values to keys, or the keys of the catalogues allowing it (asked 12 November 2026; until then `webhook-list` shows `*` as it is and the tooltip explains it) | F5 |
| A1 | authz-library | Extension point for permissions that depend on a resource (ADR-07; until then `_perm` on rows and forms; released in authz-library 2.2.0, `PermissionContributor`, and adopted: `project/ProjectPermissions` decides `/authz/check` for a project or an issue; `_perm` stays for the screens) | F2 |
| A2 | authz-library | A configurable schema for the RBAC tables (today they go into `public`, next to the `odt_*` schemas of the modules; released in authz-library 2.2.0, `authz.rbac.schema`, and adopted: schema `authz`) | F1 |
| A3 | authz-library | Problem titles and details in English (today Italian, for example «Troppi tentativi di accesso»); released in authz-library 2.1.0 and adopted (2.2.0) | F5 |
| A4 | authz-library | Bearer access for tools: a Keycloak access token resolved to the same permissions as a session, on chosen paths (until then `platform/BearerTokenSecurity.java`, ADR-03); for the public API (ADR-21) also service accounts, the tenant in a header (`X-Tenant`) and a rate limit per user or client (asked again 12 November 2026; released in authz-library 2.2.0, `authz.bff.bearer`, and adopted: `/api/**`, `/registry/**`, `/v3/api-docs`; `platform/BearerTokenSecurity.java` removed) | F5 |
| A5 | authz-library | A read-only list of the users of the session's tenant, for pickers, behind a permission of the application (today `identity/TenantUsers.java` reads `RbacAdminService.tenantUsers` with an admin scope of its own, and, for the mails of ADR-18, with the scope of the system and no session; released in authz-library 2.2.0, `TenantDirectory`, and adopted in `identity/TenantUsers`) | F2 |
| A6 | authz-library | Personal access tokens: created, listed and revoked by their user, stored hashed, shown once, accepted by the bearer access of A4 (ADR-21; asked 12 November 2026; not needed for 1.0; released in authz-library 2.2.0, `authz.rbac.tokens`, to adopt) | F5 |

## Checks

- [End-to-end check of authz-library](check-authz-end-to-end.md) (F0).
- [Load test of the search](search-load.md) (F4, ADR-16 point 8, gate M3).
