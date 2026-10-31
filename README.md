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

Phase **F0** (start-up): the decisions to take before writing the back end are proposed as ADRs in [docs/](docs/README.md) and wait for approval. The first release, 1.0, is planned for 28 May 2027.

| Phase | Content |
| --- | --- |
| F0 | Decisions D1–D9, end-to-end check of authz-library, development environment |
| F1 | Modulith skeleton, login with authz-library, CI, the `ui/` project with navigation and theme |
| F2 | Projects, issues, workflow, comments and attachments |
| F3 | Boards, backlog and sprints |
| F4 | Search, dashboards, notifications |
| F5 | Public API and webhooks, hardening, 1.0 |

## Documentation

- [docs/](docs/README.md): architecture decision records and checks.
- [brand/](brand/README.md): logo, icons, colours and how to use them.
- [backend/README.md](backend/README.md): building and running the back end.
- [AGENTS.md](AGENTS.md): rules for working on the project.
- [HANDOFF.md](HANDOFF.md): status and next steps.

## Licence

[Mozilla Public License 2.0](LICENSE). Contributions need the [CLA](CLA.md) (see [CONTRIBUTING.md](CONTRIBUTING.md)); the name is covered by [TRADEMARKS.md](TRADEMARKS.md).
