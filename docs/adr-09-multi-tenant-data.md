# ADR-09: Multi-tenant data

Status: Proposed (31 October 2026) — decision D9 of the project plan

## Context

ms-authz has tenants (organisations, customers, offices) and puts the current one in the `SessionContext`. OpenDevTrack
can either be installed once per organisation, or hold the data of several tenants in one installation.

## Decision (proposed)

**`tenant_id` on the data, filtered from the session.**

- `odt_project.project` gets `tenant_id` (not null, indexed). Issues, boards, sprints and every other row belong to a
  project and inherit its tenant through it; no `tenant_id` copied into every table.
- Every repository reads the tenant from the `SessionContext`, in one place of the module `platform` (a component
  that the repositories use, never a parameter coming from the browser).
- The key of a project (`ODT`) is unique per tenant: `UNIQUE (tenant_id, key)`.
- The RBAC tables (`app_user`, `app_role`, `user_role`, `tenant`, `rbac_audit`) stay inside `authz-rbac`: OpenDevTrack
  reads them only through the library.

```
GET /api/issues/ODT-42   session: mario.rossi, TENANT_A
  → issue joined to project WHERE project.tenant_id = 'TENANT_A' AND project.key = 'ODT'
  → 404 if ODT belongs to TENANT_B (not 403: a tenant does not learn what another one has)
```

## Alternatives considered

- **One installation per organisation.** Simplest isolation, but one database, Keycloak realm and deployment each;
  and the tenants of ms-authz would go unused.
- **One PostgreSQL schema per tenant.** Strong isolation, but migrations multiplied by the tenants, and the
  Spring Modulith schemas per module would multiply too.
- **Row-level security of PostgreSQL** as the only barrier. A good second line, considered for F5; as the only one
  it hides the rule from the code and the tests.

## Consequences

- The module tests of every module try two tenants and check that one never sees the other's data (risk «data of a
  tenant visible to another» of the plan).
- Search (ODQL) and reports add the tenant filter themselves: the jOOQ queries of ADR-04 take it from the same
  component.
- Data of a tenant can be exported or deleted by its projects.
