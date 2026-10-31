# ADR-07: Permission format

Status: Proposed (31 October 2026) — decision D7 of the project plan

## Context

Permissions appear in three places: `@PreAuthorize` in Java, `can()` in the CEL expressions of the Quadrava screens,
and the catalogue of `authz-rbac`. Quadrava accepts only `domain.resource.action` (its schemas refuse anything else).
The first ms-authz 2.0 used `resource:action`.

ms-authz 2.0.1 adopted the canonical format (migration `V3__permission_format_last_tenant.sql`): lowercase letters and
digits, three parts separated by dots, `CHECK (permission_code ~ '^[a-z0-9]+\.[a-z0-9]+\.[a-z0-9]+$')`. Codes ending in
`global` are reserved to `GLOBAL_ADMIN`.

## Decision (proposed)

**One string everywhere, `odt.<resource>.<action>`**, the same in Java, in the JSON of the screens and in the catalogue.

| Level | Example | Where it is granted |
| --- | --- | --- |
| Global per tenant | `odt.project.create` | `authz-rbac` (catalogue from Flyway migrations of OpenDevTrack) |
| Project role | `odt.issue.transition` | module `access`, from the role of the user in the project |
| Single row | `row._perm.transition` | computed by `access` in every answer |

- No translation layer in `access`: a code is never renamed between back end and front end.
- **The catalogue is registered in the ms-authz database by a script** (decided by the maintainer): a Flyway migration
  of OpenDevTrack in its own folder, added to the ones of `authz-rbac` (`authz.rbac.flyway.locations:
  classpath:db/authz/migration,classpath:db/authz/odt`), the mechanism the ms-authz demo uses for its data. Proposed
  form: a **repeatable** migration `R__odt_permission_catalogue.sql` with `INSERT … ON CONFLICT DO NOTHING` for the
  permissions, the global roles and their links. A repeatable one runs after the versioned ones and again when it
  changes, so it never collides with the next `V4`, `V5` of ms-authz and needs no `out-of-order` in production. A
  permission is removed by a versioned migration of its own, never by editing the list.
- No hyphens, no underscores, no fourth part (`odt.issue.edit.own` is refused by the constraint): an action with a
  scope gets its own word (`odt.issue.editown`) or a row permission.
- Permissions of ms-authz itself keep their own domain (`authz.rbac.manage`).

## Alternatives considered

- **`resource:action` translated by `access`.** Two names for the same thing, and a mapping to test and keep in sync.
  No longer needed since ms-authz 2.0.1.

## Consequences

- **Gap in ms-authz 2.0.1** (checked in `AuthzController`): `/authz/capabilities` answers only the permissions of the
  session, and `/authz/check` ignores `resource`. There is no extension point for the permissions that depend on a
  project. Request to ms-authz (**A1**, in its repository): an optional bean, for example a
  `PermissionContributor`, that adds permissions for the session and decides the checks with a `resource`; without
  it, today's behaviour. Needed by F2 (project roles); F1 only needs the global permissions.
- The Definition of Done keeps its check «permission defined and tried with a user without it».
