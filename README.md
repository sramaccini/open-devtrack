<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="brand/svg/odt-logo-horizontal-dark.svg">
    <img src="brand/svg/odt-logo-horizontal.svg" alt="OpenDevTrack" width="360">
  </picture>
</p>

# OpenDevTrack

An open-source issue tracker for software teams: projects, issues with custom fields, configurable workflows, Kanban and Scrum boards, backlog and sprints, search with a query language, dashboards and reports.

- **Front end**: a [Quadrava](https://github.com/sramaccini/quadrava) project, JSON documents only (screens, data sources, navigation, themes, translations), drawn by the Quadrava runtime taken as a versioned dependency. OpenDevTrack contains no Angular code and no copy of the runtime.
- **Back end**: one Spring Boot deployable divided into Spring Modulith modules, on PostgreSQL.
- **Login, session and global roles**: the [authz-library](https://github.com/sramaccini/authz-library) libraries (`authz-bff`, `authz-session`, `authz-rbac`) embedded in the back end, Keycloak as identity provider, sessions on Redis. No token reaches the browser.

What is missing in Quadrava or in authz-library is added there, with their own tests and releases, never with a local patch.

## Status

Phases **F0–F4 done**: the back end, the `ui/` project and CI are in place, with projects, issues, workflow, comments, attachments, boards, backlog and sprints, search, dashboards and notifications. Next is **F5**. The decisions are ADRs in [docs/](docs/README.md); [HANDOFF.md](HANDOFF.md) has the details and the next steps. The first release, 1.0, is planned for 28 May 2027.

| Phase | Content | State |
| --- | --- | --- |
| F0 | Decisions D1–D9, end-to-end check of authz-library, development environment | Done |
| F1 | Modulith skeleton, login with authz-library, CI, the `ui/` project with navigation and theme | Done |
| F2 | Projects, issues, workflow, comments and attachments | Done (gate M1); also mentions and in-app messages (ADR-13) |
| F3 | Boards, backlog and sprints | Done (ADR-15) |
| F4 | Search, dashboards, notifications | Done (gate M3: search load test) |
| F5 | Public API and webhooks, hardening, 1.0 | Next (ADR-21 proposed) |

## Documentation

- [docs/](docs/README.md): architecture decision records and checks.
- [brand/](brand/README.md): logo, icons, colours and how to use them.
- [stack/README.md](stack/README.md): the whole application in Docker with sample projects, to try it (`stack/stack.sh up`).
- [backend/README.md](backend/README.md): building and running the back end.
- [AGENTS.md](AGENTS.md): rules for working on the project.
- [HANDOFF.md](HANDOFF.md): status and next steps.

## Licence

[Mozilla Public License 2.0](LICENSE). Contributions need the [CLA](CLA.md) (see [CONTRIBUTING.md](CONTRIBUTING.md)); the name and the logo are covered by [TRADEMARKS.md](TRADEMARKS.md).
