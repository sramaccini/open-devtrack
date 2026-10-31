# Documentation

## Architecture decision records

The decisions D1–D9 of the project plan, to approve before phase F1 starts (end of F0: 27 November 2026). A proposed
decision is not applied until it is accepted.

| ADR | Decision | Proposal | Status |
| --- | --- | --- | --- |
| [01](adr-01-quadrava-distribution.md) | How OpenDevTrack gets Quadrava (D1) | The runtime image of a pinned version, the CLI in CI | Proposed |
| [02](adr-02-java-version.md) | Java version (D2) | Java 25 LTS, required by ms-authz 2.0.1 | Proposed |
| [03](adr-03-screen-definitions-in-production.md) | Where the screens come from in production (D3) | A registry module in the monolith, started from the Quadrava reference | Accepted (direction) |
| [04](adr-04-persistence.md) | Persistence for search and reports (D4) | JPA for commands, jOOQ for search and reports | Proposed |
| [05](adr-05-licence.md) | Licence (D5) | MPL-2.0 with CLA, as Quadrava | Accepted |
| [06](adr-06-board-columns-from-data.md) | Board columns from data (D6) | `columnsFrom` of the Quadrava `kanban`, one generic board screen | Proposed |
| [07](adr-07-permission-format.md) | Permission format (D7) | `odt.<resource>.<action>` everywhere, catalogue by a migration script; ms-authz A1 for project permissions | Proposed |
| [08](adr-08-tenant-selection.md) | Choosing the tenant (D8) | Optional selector of the Quadrava shell, last tenant at login | Proposed |
| [09](adr-09-multi-tenant-data.md) | Multi-tenant data (D9) | `tenant_id` on the project, filtered from the session | Proposed |

## Requests to the dependencies

Found while writing the ADRs; each is built in its own repository, optional for the other users.

| # | Where | What | Needed by |
| --- | --- | --- | --- |
| Q2 | Quadrava | Release 0.2.0 with the CLI `quadrava validate` and `publish` (ADR-01, ADR-03) | Start of F1 |
| A1 | ms-authz | Extension point for permissions that depend on a resource (ADR-07) | F2 |

## Checks

- [End-to-end check of ms-authz](check-authz-end-to-end.md) (F0).
