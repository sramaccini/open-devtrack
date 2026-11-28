# Changelog

What changes in each release of OpenDevTrack, for those who install and use it. The format is
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and from 1.0 the versions follow
[Semantic Versioning](https://semver.org/): within 1.x, no public path or field of the API is removed or renamed
([docs/api.md](docs/api.md)), and every release upgrades from the one before ([docs/release.md](docs/release.md)).
The entries are written by hand at each release from the commits ([docs/release.md](docs/release.md)).

## [Unreleased]

### Added

- **Issues**: parent and subtasks, as in Jira (ADR-28). The new type «Subtask», and «Parent» in the details of an issue: an
  epic for a story, task or bug, a story, task or bug for a subtask, in the same
  project. The page of an issue lists its subtasks («Child work items» on an epic) with their progress, and «Add
  subtask» creates one with its summary. A subtask has no story points and is not in the backlog; deleting an issue
  deletes its subtasks after a confirm that names them, and a story moves to another project with its subtasks. ODQL
  gets `parent = WEB-1` and the type `subtask`; the export gives the parent and the children. API: `parent` in
  `POST /api/projects/{key}/issues` and in the `PATCH` of an issue, `parent` and `parentKey` in the issue,
  `GET /api/issues/{key}/children`, `subtasks` and `detached` in the answer of a move (#55 T5).
- **Backlog**: the panel of the epics, as in Jira: the epics not done with their progress; a click shows the issues
  of an epic, «All issues» and «Without epic» the others. The column Epic, and «Set epic…» on an issue or on the
  chosen ones (`GET /api/projects/{key}/epics`, `POST /api/projects/{key}/epic`); the rows of the search carry
  `parent` (#55 T5).
- **Search**: the ODQL functions `standardIssueTypes()` and `subTaskIssueTypes()` for the type, as in Jira (#55 T5).
- **Issues**: the parent of an issue in the path above its title, as in Jira (#61).
- **Issues**: «+» under the summary, as in Jira: add a subtask (a child on an epic), an attachment or a link without
  going down to their sections (#55 T15).
- **Issues**: linked work items grouped by relation, and «Add linked work item» opens in the section instead of a
  dialog, as in Jira (#55 T6).
- **Administration**: link types configured by the admin of the tenant, as in Jira (ADR-29). «Link types» lists them
  with their links, adds one with a description from each end, changes the descriptions and removes a type, moving its
  links to another or deleting them. The four types of before stay, translated while unchanged; `clones` cannot be
  removed. API: `GET/POST /api/link-types`, `PATCH/DELETE /api/link-types/{code}` (`replaceWith`, `deleteLinks`);
  a link carries `type`, `outward` and `label` besides `relation`, and a new type reads `code` from one end and
  `code:inward` from the other (#55 T6).
- **Issues**: the start date, as in Jira: in Details, the new issue and the export, never after the due date; the
  column Start of the search and `start` in ODQL. API: `startOn` in the issue, `POST`, `PUT` and `PATCH` (#55 T10).
- **Search**: links in ODQL, as in Jira (ADR-29): `linked = WEB-4`, `linkType = blocks` and
  `key IN linkedIssues(WEB-4)` or `linkedIssues(WEB-4, blocks)`; a function of ODQL may now take arguments (#55 T6).

### Changed

- **Issues**: «Assign to me» is a link under Assignee, as in Jira, in place of the item of «…» (#55 T4).
- Quadrava 0.34.0: runtime image, CLI, schemas and kits.

### Upgrading

- The migrations add the column of the parent and the type `subtask`; no existing issue has a parent. A workflow
  scheme without `subtask` gives subtasks its default workflow. The default query of the backlog changes: a saved
  filter made from it still lists subtasks until `type IN standardIssueTypes()` is added.
- The migration `V44` adds the table of the link types and stores the type of each link in lowercase (`blocks`,
  not `BLOCKS`); the relations of the API (`blocks`, `blocked_by`, …) do not change.
- The migrations `V46` and `V47` add the start date to the issues and to the index, empty for every issue.
- The migration `V45` adds the links to the search documents, from the links already there: no rebuild of the
  index is needed.

## [1.0.0-rc.10] - 2026-11-27

### Added

- **Issues**: «Move» in the «…» of an issue, as in Jira (ADR-27): to another project of the organisation, for the
  admins of the project (new permission `odt.issue.move`, added to the admins of every scheme) who can create issues
  there; the dialog offers only those projects and the statuses there. The issue gets a new key and the old one keeps
  leading to it; the screen confirms what would be lost first.
  Comments, attachments, history, links, watchers and votes follow it. A new notification «An issue you watch moved»
  and a webhook event `issue.moved`, sent to both projects (`POST /api/issues/{key}/move`) (#55).
- **Issues**: «Export» in the «…» of an issue, as in Jira, for whoever sees it: a Word document (.docx), an
  OpenDocument text for LibreOffice (.odt), both with the fields, description, links, attachments and comments in the
  language of the user, or JSON with everything, history included (`GET /api/issues/{key}/export?format=`) (#55).
- **Issues**: the reporter of an issue can be changed, as in Jira, by whoever has the new project permission
  `odt.reporter.edit` (the admins in every scheme, also those already made; `PATCH /api/issues/{key}` with
  `reporter`, a member of the project). The change is in the history and the new reporter watches the issue (#55).
- **Issues**: «Copy link» in the header of an issue, as in Jira: the address of the issue in the clipboard.
- **Issues**: «Share» in the header of an issue, as in Jira: up to ten members of the project who see it, with an
  optional message, get the new notification «An issue shared with you», by default also by email at once
  (`POST /api/issues/{key}/share`) (#55).
- **Issues**: the page shows when the issue was created and last updated, under Planning, as in Jira (T16).
- **Issues**: «Assign to me» in the «…» of an issue, as in Jira, for those who edit it (#55 T4).
- **Boards and search**: a flagged issue has a red «Flagged» label on its card and in the backlog, and the new
  ODQL field `flagged` (`Impediment` or empty, as in Jira) finds them: `flagged IS NOT EMPTY` (#55).
- **Issues**: «Clone» in the «…» of an issue, as in Jira: a copy in the same project with its fields, labels and
  custom fields, linked to the original by the new relation «clones» / «is cloned by» (`POST /api/issues/{key}/clone`);
  and «Print», the page without menus and buttons (#55).
- **API**: `PATCH /api/issues/{key}` changes only the fields it names, with the version read and the same checks as
  `PUT`; null clears a field, and the custom fields given leave the others as they are.

### Changed

- **Backlog, search and dashboard**: a flagged issue stands out there too, as on the boards: its row gets the yellow
  edge and background with the flag, in place of the red label of the backlog. The rows of `POST /api/search` carry
  `flag` (`impediment` or null). **Quadrava 0.32.0** (runtime image, CLI, schemas and kits): `highlight` on
  `dataTable` and `serverDataTable` (Q50), and SVG images of asset packs kept as they are, so the logos of the
  themes are again the SVG files of `brand/` (#59).
- **Brand**: the logos and icons of the themes come with the screens, as the asset pack `odt` in the registry
  (`ui/assets/odt.json`, made from `brand/pack.json`), on **Quadrava 0.31.0** (runtime image, CLI, schemas and kits;
  asset packs, ADR-29 of Quadrava). The runtime no longer mounts `brand/svg` and `brand/png`: an installation without
  Compose drops those two mounts. The registry accepts the document kind `assets` (registry contract 0.8) (#60).
- **Boards and issue list**: a flagged issue stands out, as in Jira: its card on the boards and its row in the list
  of the issues of a project get a yellow edge and background with the flag, named «Flagged» for screen readers (the
  red label of the cards gives way to it). **Quadrava 0.30.0** (runtime image, CLI and
  schemas): `highlight` on `kanban` and `table` (Q46), and the Ant Design icons loaded only when shown.
- **Quadrava 0.29.0** (runtime image, CLI and schemas): the `copy` and `copyLink` steps (Q45), every Ant Design icon by
  name, and an emptied number of a form sent as null (the back end already reads it as a field not given).
- **Issues**: as in Jira, the reporter of an issue does not vote for it, and the votes of a done issue no longer
  change (409); `_perm.vote` tells the page, which disables the thumb (#55).
- **Issues**: the watchers of an issue are in a window, «Watchers» in the menu of the eye, with «Add watcher…»; the
  box of the watchers at the bottom of the right column is gone, as in Jira.
- **Issues**: the summary and the description fill their box. Under the attachments a dashed area with «Add attachment»
  takes a file chosen or dropped on it, and «Add linked work item» sits in the same area under the links, as in Jira
  (#52). The arrows of the story points stop at 0 and 999.
- **Issues**: the summary, as a title, and the description read as text and are changed in place, as in Jira: a click
  opens one of them, the summary is confirmed with ✓ or Enter, the description with «Save» or Ctrl+Enter, and each is
  saved on its own; the «Save» button of the two goes (#53).
- **Issues**: the fields of Details and the story points of Planning are changed in place in the same way: a choice
  from a list is saved as soon as it is picked, the others with ✓ or Enter; the «Save» buttons of the two boxes go
  (#54). Each field sends only itself, with `PATCH /api/issues/{key}`.
- **Issues**: the sprint of an issue is chosen in place under «Planning», among the sprints not completed of the
  project or the backlog, as in Jira; the plan of an issue in the API also gives `sprintId` and `_perm.plan`.
- Quadrava 0.28.0 (runtime image and CLI): the drop zone of a file field and the dashed box (Q42, 0.26.0), editing in
  place in a form (Q43, 0.27.0), lookups, selects and checkboxes in place (Q44).

### Fixed

- **Backlog**: the red «Flagged» label of a flagged issue never showed, because the rows of the search have no flag;
  they have it now, and the label became the highlight of the row.
- **Telemetry**: the traces and logs that the screens send no longer get 404: the back end implements the
  telemetry contract of Quadrava (`/telemetry/v1/traces`, `/telemetry/v1/logs`), masks personal data again and
  forwards them to the collector of `ODT_TELEMETRY_OTLP_ENDPOINT`, if set (#57).

### Upgrading

From 1.0.0-rc.9: put the digest of the new image in `.env`, then `./odt.sh up` and `./odt.sh publish-ui`
([upgrades-and-backups.md](docs/admin/upgrades-and-backups.md)). The schema changes by itself at start-up (the old keys
of moved issues, the permission `odt.issue.move` for the admins of every scheme). The runtime no longer needs
`brand/svg` and `brand/png` mounted: an installation without Compose can drop those two mounts. New optional
settings: `ODT_TELEMETRY_OTLP_ENDPOINT` and `ODT_TELEMETRY_MAX_BYTES` ([configuration.md](docs/admin/configuration.md)).

## [1.0.0-rc.9] - 2026-11-26

The ninth candidate: the page of an issue closer to Jira, in two columns, with the status as a button with its
workflow, the actions as icons, the flag and the votes; the «Polar night» theme; on Quadrava 0.25.1.

### Added

- **Issues**: the flag and the votes of an issue, as in Jira. «Add flag» in the «…» menu marks it as impeded, in its
  history, shows a red flag next to its status and asks, in a panel, for a comment to tell the team why; the thumb
  next to the status, or «Add vote», counts who wants it done. The API has
  `PUT`/`DELETE /api/issues/{key}/flag` and `GET`/`POST /api/issues/{key}/votes`, `DELETE …/votes/me`; an issue has
  `flagged`, `votes` and `voted`. The votes are in the export of the personal data and are deleted with the user.
- **API**: `GET /api/issues/{key}/workflow`, the workflow an issue follows with its statuses and transitions, for the
  status button and the workflow graph of the issue page (#50).

- **Issues**: the status of an issue is a button at the top of its right column, as in Jira: its menu lists the
  statuses the issue can move to now, each as a coloured badge, and «View workflow», which opens a window with the
  graph of the workflow and the current status outlined (#50). It replaces the box of the next steps; the status is no
  longer a field of the details.
- **Issues**: the actions of an issue are two icon buttons next to its status, as in Jira: the eye with the number of
  watchers, highlighted while you watch (watch, stop watching, add a watcher), and «…» (all issues, delete) (#51).

- **Themes**: «Polar night», the dark theme of the Quadrava examples, with the logo of OpenDevTrack; chosen in the
  settings, next to OpenDevTrack light and dark. An installation lists it in `QUADRAVA_THEMES`.

### Changed

- **Issues**: the page of an issue is in two columns, as in Jira: summary, description, attachments, linked work
  items and activity on the left; the status, the actions, details, planning and watchers on the right. The summary
  and the description, and the details, are saved separately.
- **API**: the transitions of an issue have the `category` of the status they lead to.
- Quadrava 0.25.1 (runtime image and CLI): the menu button, the window step and the graph (Q39, 0.23.0); icon-only
  buttons with a count and a pressed state (Q40, 0.24.0); the icons flag and like, a tone on a pressed button and the
  popover step (Q41).

### Fixed

- **Installation**: with the profile keycloak, the back end could start while Keycloak still answered 503, stop, and
  make `./odt.sh up` fail although a restart would have recovered it. Keycloak is now healthy only once its realm
  answers, and the back end waits for that (#48).

## [1.0.0-rc.8] - 2026-11-25

The eighth candidate: the menus of the dark theme in place, on Quadrava 0.22.1, and an image built without Maven
Central in Docker.

### Changed

- The image of the back end is built from the jar that the release builds with Maven outside Docker, with its
  restored repository: the build in Docker downloaded every dependency from Maven Central again and failed on 429
  (#47). `backend/Dockerfile` still builds from the sources by default.
- Quadrava 0.22.1 (runtime image and CLI).

### Fixed

- In the dark theme, menus and drop-downs (the «Move» menu of a card, row actions, column filters) opened above their
  button, often out of sight, on a scrolled page (Quadrava 0.22.1).

## [1.0.0-rc.7] - 2026-11-25

The seventh candidate: cards that change row on a board, on Quadrava 0.22.0.

### Added

- **Boards**: on a board with lanes by assignee, priority or type, a card moved to another row changes that field of
  its issue, after a confirmation, with the rules of the issue form and in one change with the transition of its
  column (Q38). `POST /api/boards/{id}/cards/{issueKey}/move` takes `lane`.

### Changed

- Quadrava 0.22.0 (runtime image and CLI).

### Upgrading

From 1.0.0-rc.6: put the digest of the new image in `.env`, then `./odt.sh up` and `./odt.sh publish-ui`
([upgrades-and-backups.md](docs/admin/upgrades-and-backups.md)). No change of data and no new setting.

## [1.0.0-rc.6] - 2026-11-25

The sixth candidate: the project page in tabs and breadcrumbs on every page, on Quadrava 0.21.0. The tag
`v1.0.0-rc.5` holds the same changes, but its release was never published: its image did not build, because Maven
Central refused the downloads (429), which this candidate retries longer.

### Changed

- **Project page**: in tabs, as the issue page: General, Boards, Members, Groups, Workflows and Custom fields. The
  buttons that add to a part («New board», «Add member», «Add group», «New workflow», «New field») are in its tab,
  and the description takes the whole line.
- **Breadcrumbs**: every page shows its path above the title, for example Projects › WEB › Issues › WEB-4, and each
  step before the last one leads back to its page (Q37).
- Quadrava 0.21.0 (runtime image and CLI).
- Building: Maven retries Maven Central eight times, ten seconds apart and growing, when it answers 429 or 503
  (`backend/.mvn/maven.config`).

### Upgrading

From 1.0.0-rc.4: put the digest of the new image in `.env`, then `./odt.sh up` and `./odt.sh publish-ui`
([upgrades-and-backups.md](docs/admin/upgrades-and-backups.md)). No change of data and no new setting.

## [1.0.0-rc.4] - 2026-11-25

The fourth candidate: the screens on Quadrava 0.20.1, which closes the two accessibility points left open by rc.3.

### Changed

- Quadrava 0.20.1 (runtime image and CLI).
- The theme chosen in Settings is remembered by the browser, as the language already was: a reload, a new tab or the
  next sign-in keep it (Q36).

### Accessibility

- The badges of the cards of a board are readable in the dark theme: their text takes the colour the theme computes
  for their fill, about 8:1 (Q35) ([docs/accessibility.md](docs/accessibility.md)).

### Upgrading

From 1.0.0-rc.3: put the digest of the new image in `.env`, then `./odt.sh up` and `./odt.sh publish-ui`
([upgrades-and-backups.md](docs/admin/upgrades-and-backups.md)). No change of data and no new setting.

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
