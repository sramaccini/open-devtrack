# ADR-10: Workflows and their CEL guards

Status: Proposed (31 October 2026) — needed by F2 E3; not applied until accepted

## Context

The plan (E3, module `workflow`) wants statuses, transitions, conditions and validators in CEL, and workflow schemes by
issue type; its data model has `transition (workflow_id, from_status, to_status, guard_cel, validators)`. It leaves open
which CEL engine the back end uses, where the default workflow comes from, and how the board (ADR-06) and the screens
learn the transitions allowed. Today (F2 E2) every issue is created `todo` and never moves: transitions are the piece of
gate M1 still missing.

The screens already speak CEL: Quadrava evaluates `visibleIf`, `readOnlyIf` and the like with `can()`, `row`, `form`
(ADR-07). Guards written in the same language can be read by the same people.

## Decision

1. **Data** (`odt_workflow`, Flyway): `workflow (id, tenant_id, project_id, name)`, `status (workflow_id, code, category,
   position)` with the category one of `todo`, `in_progress`, `done` (for boards and reports), `transition (id,
   workflow_id, from_status, to_status, name, guard_cel, required_fields)`, and `workflow_scheme (project_id, issue_type,
   workflow_id)`. A transition with no `from_status` is allowed from every status.
2. **Default workflow**: when a project is created, the module `workflow` (listening to a `ProjectCreated` event of
   `project`) gives it a copy of the built-in one: To do → In progress → Done, Done → To do (reopen), with no guard. Each
   project then changes its own copy; a template shared by the tenant comes later, if needed.
3. **Guards in CEL**, with **cel-java** (`dev.cel:cel`, Google; the newest release at least two weeks old, 0.14.0
   today). The variables are `issue` (the fields of the issue after the transition, as a map), `user` (`username`,
   `roles`) and `can(permission)` with the project permissions of the user, the same names as in the screens. Example:
   `issue.assignee != null && can('odt.issue.transition')`. An expression is compiled when it is saved (a syntax or type
   error is a 400 with the position) and cached compiled; it evaluates with a time limit and no I/O.
4. **API**: `GET /api/issues/{key}/transitions` gives the transitions allowed now (the guard is evaluated for each, so
   the screens show only what can be done, as `_perm`); `POST /api/issues/{key}/transitions {transition, version}` runs
   one: `odt.issue.transition` in the project, the guard true, the `required_fields` filled, then the status changes in
   the transaction of the module `issue`, which publishes `IssueChanged` (field `status`) for the changelog.
5. `issue` asks `workflow` (its public API, `Workflows.check(…)`) and keeps the status; `workflow` does not know the
   entity of the issue, only the map it receives. No cycle: `workflow` depends on `project` and `access`, `issue` on
   `workflow`.

## Alternatives considered

- **SpEL** (already in the classpath). No new dependency, but a second expression language beside the CEL of the
  screens, and SpEL can reach beans and types unless locked down carefully: a bigger surface for expressions written by
  project admins.
- **A rule engine** (Drools and the like). Too heavy for a guard of one line (AGENTS.md: light, governable solutions).
- **Transitions in code only** (a fixed enum). Simple, but the plan asks for workflows configured per project and type.

## Consequences

- New dependency `dev.cel:cel` (with protobuf and Guava, which it brings). It is pre-1.0: the version is pinned and the
  guards are covered by tests of the expressions the default workflow and the documentation use.
- The screen of an issue shows one button per transition allowed (a data source over `GET …/transitions`); the board
  (F3, ADR-06) calls the same `POST` when a card moves to another column.
- Editing workflows (statuses, transitions, guards) is a screen of the project admin, after the transitions themselves.
