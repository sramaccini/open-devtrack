# Changelog

What changes in each release of OpenDevTrack, for those who install and use it. The format is
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and from 1.0 the versions follow
[Semantic Versioning](https://semver.org/): within 1.x, no public path or field of the API is removed or renamed
([docs/api.md](docs/api.md)), and every release upgrades from the one before ([docs/release.md](docs/release.md)).
The entries are written by hand at each release from the commits ([docs/release.md](docs/release.md)).

## [Unreleased]

The first release, 1.0.0, planned for 28 May 2027. What it holds, by area:

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
  personal access tokens; webhooks per project signed as Standard Webhooks (ADR-21).
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
