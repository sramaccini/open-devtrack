# ADR-08: Choosing the tenant

Status: Accepted (31 October 2026) — decision D8 of the project plan

## Context

A user of authz-library may belong to several tenants. The first authz-library 2.0 sent such a user to
`login-error?error=tenant_selection_required`, and Quadrava had no page to choose: the user looped on the login.

Both sides are now done, following the direction of the plan:

- Quadrava ADR-18 (`contracts/bff.yaml` 0.3.0, optional part): the user may carry `tenants: [{ id, label }]` and
  `currentTenant`; `POST /bff/tenant` with `{ "tenant": "<id>" }` and the CSRF token switches; the shell shows a
  selector in the user menu only when `tenantSwitchUrl` is configured and the list has more than one entry.
- authz-library 2.0.1: the login opens the session on the last tenant used (then the first by code), never asks; the switch
  is a POST, keeps the session id and its absolute expiry, and recomputes the permissions.

## Decision

**Optional selection in the Quadrava shell, as implemented.** OpenDevTrack sets `QUADRAVA_TENANT_SWITCH_URL=/bff/tenant`
on the runtime image; after a switch the runtime reloads permissions, navigation and the current screen.

## Alternatives considered

- **A public page to choose the tenant at login.** One more screen outside Quadrava, a question at every login, and a
  GET flow that the `SameSite=Lax` session cookie would make reachable from external links.

## Consequences

- A user with one tenant sees nothing new.
- Each screen must reload its data after a switch, which the runtime does; the back end must never cache per-session
  data outside the session (the tenant is read from the `SessionContext` at each call). See ADR-09.
- Checked end to end in F0 with two tenants (`docs/check-authz-end-to-end.md`).
