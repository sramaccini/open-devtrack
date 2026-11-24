# Changelog

What changes in each release of OpenDevTrack, for those who install and use it. The format is
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and from 1.0 the versions follow
[Semantic Versioning](https://semver.org/): within 1.x, no public path or field of the API is removed or renamed
([docs/api.md](docs/api.md)), and every release upgrades from the one before ([docs/release.md](docs/release.md)).
The entries are written by hand at each release from the commits ([docs/release.md](docs/release.md)).

## [Unreleased]

## [1.0.0-rc.3] - 2026-11-24

The third candidate: the screens on Quadrava 0.20.0, which answers the manual accessibility check of rc.2.

### Changed

- Quadrava 0.20.0 (runtime image and CLI).

### Accessibility

- Tables by keyboard: the titles of the sortable columns are buttons, and each column filter is a button named after
  its column, whose panel opens with Enter and closes with Escape (Q31).
- A focus ring in the colours of the theme on every control, and the focus back on the button that opened a dialog
  when it closes (Q32).
- Names and states for screen readers: the selection checkboxes are named, required fields and errors are announced
  with their field, the close button of dialogs is translated, and the browser tab names the screen («Backlog ·
  OpenDevTrack») (Q33).
- The dark theme keeps the colours of the brand, with no text below 4.5:1 on the screens checked (Q34).
- Still open, asked to Quadrava: the badges of the cards of a board in the dark theme (Q35), and remembering the chosen
  theme across visits (Q36) ([docs/accessibility.md](docs/accessibility.md)).

### Upgrading

From 1.0.0-rc.2: put the digest of the new image in `.env`, then `./odt.sh up` and `./odt.sh publish-ui`
([upgrades-and-backups.md](docs/admin/upgrades-and-backups.md)). No change of data and no new setting.

## [1.0.0-rc.2] - 2026-11-24

The second candidate: the backlog as a table, the screens on Quadrava 0.19.1, and the result of the manual
accessibility check of rc.1.

### Added

- **Backlog**: a search of the issues of the sprints not completed and of the backlog, in a table grouped by sprint,
  with the query, the sort, the filters and the paging of the search (ADR-26). Each row moves to a sprint, to the top
  or to the bottom of the order; the chosen rows move to a sprint together. `POST /api/projects/{key}/backlog/plan`
  plans up to 200 issues at once, all or none.
- **Search**: the column and the group Sprint.
- **Screens**: an icon on every button and action; the issue page in tabs (General, Planning, Next steps), its
  activity in a card of its own, its description across the whole line, its heading with the key of the issue.
- **Demo**: the public demo at demo.ods.logicwarez.com, with alerts through an external monitor
  (`deploy/demo`).

### Changed

- Quadrava 0.19.1 (runtime image and CLI): light group rows and selected rows in the tables, the forwarded headers of
  a proxy with `QUADRAVA_TRUST_FORWARDED` ([installation](docs/admin/installation.md)).
- A change of plan of an issue is in the search index when the move answers, so the backlog shows it at once.

### Removed

- `GET /api/projects/{key}/backlog/cards`, the cards of the backlog board, replaced by the search (ADR-26). The
  candidates come before the promise of 1.x on the API ([docs/api.md](docs/api.md)).

### Accessibility

The manual check of rc.1 ([docs/accessibility.md](docs/accessibility.md)) fails on points that are in the components
of Quadrava and are asked there: the sorting and the filters of the tables by keyboard (Q31), the focus ring and the
focus after a dialog (Q32), names and states for screen readers (Q33) and the colours of the dark theme (Q34). The
light theme, reflow at 320 px, the board and the backlog by keyboard pass.

### Upgrading

From 1.0.0-rc.1: put the digest of the new image in `.env`, then `./odt.sh up` and `./odt.sh publish-ui`
([upgrades-and-backups.md](docs/admin/upgrades-and-backups.md)). No change of data and no new setting.

## [1.0.0-rc.1] - 2026-11-23

The first candidate of the first release, 1.0.0, planned for 28 May 2027. What it holds, by area:

### Added

- **Projects and issues**: projects per tenant with members and roles; issues with types, priorities, labels, due
  date, reviewer, custom fields, links, watchers, comments with mentions, attachments and their history.
- **Workflows**: statuses and transitions per project with guards in CEL, edited by the project admin, several
  workflows mapped by issue type (ADR-10, ADR-20).
- **Boards, backlog and sprints**: Kanban and Scrum boards of the workflow statuses with WIP limits and swimlanes, the
  backlog with one rank per project, sprints (ADR-15).
- **Search**: full text, structured filters and the query language ODQL, with saved and shared filters (ADR-16).
- **Dashboards and reports**: a personal and a project dashboard; burndown, velocity, cumulative flow, created vs
  resolved, cycle time (ADR-17).
- **Notifications**: in-app messages and mail, instant or as a daily digest, with preferences per user (ADR-13,
  ADR-18).
- **Public API and webhooks**: the REST API with its OpenAPI 3.1 definition, Keycloak tokens, service accounts and
  personal access tokens; webhooks per project signed as Standard Webhooks (ADR-21), only to public addresses, and
  to a list of hosts with `ODT_WEBHOOKS_ALLOWED_HOSTS` (ADR-25).
- **Administration**: users, roles, tenants, sessions, groups, permission schemes, and the audit log of the
  configuration (ADR-22).
- **Privacy**: the export of a user's data, and their removal with a pseudonym (ADR-24).
- **Security**: OWASP ASVS 4.0.3 level 2 ([docs/security/asvs.md](docs/security/asvs.md)), WCAG 2.2 AA checked with
  axe-core, a recent login before the sensitive actions, encrypted secrets with an optional Vault transit key,
  quota, type check and antivirus for attachments, rate limits, metrics and alert rules (ADR-23, ADR-24).
- **Installation**: `deploy/compose` with Caddy and Keycloak as options, the realm template, and the administrator
  guide in [docs/admin](docs/admin/README.md). The images are signed, with their SBOM (ADR-24, ADR-25).
- **Documentation**: the user guide in [docs/guide](docs/guide/README.md), with the reference of ODQL (ADR-25).

### Dependencies

- Quadrava 0.18.0 (runtime image and CLI), authz-library 2.6.0, Keycloak 26.8.0, PostgreSQL 18, Redis 8.2, Java 25.
