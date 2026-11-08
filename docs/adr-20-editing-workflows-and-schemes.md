# ADR-20: Editing workflows, and workflows by issue type

Status: Accepted (8 November 2026; decided by the maintainer) — after F4; the two pieces ADR-10 left for when a
project needs them

## Context

ADR-10 gave each project a copy of the built-in workflow (To do → In progress → Done, with reopen) and left two
things for later: editing the statuses and transitions («a screen of the project admin»), and workflow schemes by
issue type (`workflow_scheme (project_id, issue_type, workflow_id)`; «the tables already allow several workflows per
project»).

What there is today:

- `odt_workflow`: `workflow (project_id UNIQUE)`, `status (workflow_id, code, category, position)`, `transition
  (workflow_id, code, from_status, to_status, guard_cel, position)`. The only change allowed is the guard of a
  transition (`PUT …/workflow/transitions/{code}`, `odt.project.edit`), shown in the Workflow section of
  `project-detail`.
- The first status by position is the one of a new issue. Status and transition codes are stored in issues, board
  columns (`odt_agile.board_column`), saved filters and board filters (ODQL), the changelog and the read models of
  `search` and `reporting`; boards and reports group statuses by category.
- `issue` depends on `workflow` (`Workflows`): the initial status, the transitions available, the decision on one.
  `workflow` cannot call `issue` back without a cycle; `shared.IssueQueries` (ADR-16) shows the way round.
- Built-in statuses and transitions are translated from the catalogues (`values.status.*`, `values.transition.*`);
  any other is shown by its code.
- A board with its own columns lists the statuses of each column; a status in none of them has no column.

## Decision

Two steps, each its own change, A before B.

### A. Editing the workflow of a project

1. **Who**: an admin of the project (`odt.project.edit`), as for the guards today. The workflow gets a `version`
   (412 when changed meanwhile).
2. **Statuses**: add one (code, category, position), change its category or position, remove it. The **code never
   changes** once created: it is the key everywhere else. The first status must be in the category `todo` (it is the
   one of new issues), and each category keeps at least one status, so boards and reports always have the three.
3. **Removing a status** asks for `moveTo`, another status of the workflow: its issues move there, then the status
   and the transitions from or to it go. The move is done by `issue` through an interface of `shared`
   (`IssueStatuses.move(projectId, from, to)`, implemented by `issue`, as `IssueQueries`), with one `IssueChanged`
   (`status`) per issue, so the changelog, `search` and `reporting` follow. Saved filters or board filters that name
   the code stop compiling and say so (ADR-16), as after a renamed sprint.
4. **Transitions**: add one (code, from or any status, to, guard), change from, to or guard, remove it; the code never
   changes. The guard is checked when saved, as today.
5. **Boards**: a status in no column of a board's own columns is shown in the first column holding a status of the
   same category, else in the last column, so a new status never hides cards. Read by `agile` when it builds the
   columns; nothing is written to the boards.
6. **Screens**: the Workflow section of `project-detail` gets a table of the statuses (new, edit, remove with the
   status to move to) beside the table of the transitions (new, edit, remove; the condition as today). A status or
   transition outside the catalogues is shown by its code.

### B. Workflows by issue type

1. **Data**: `workflow` loses `UNIQUE (project_id)` and gains `is_default`; `workflow_scheme (project_id, issue_type,
   workflow_id)`. Every project keeps one default workflow (today's); a type without a row uses it.
2. **More workflows**: an admin adds one as a copy of the default of the project, renames it, edits it as in A, and
   maps types to it. A workflow is removed only when no type maps to it and it is not the default.
3. **One category per code in a project**: a status code means the same in every workflow of the project (checked when
   saved). `Workflows.statuses(projectId)` returns the union by code, in the order of the default then of the others,
   for boards, ODQL and reports, which do not change.
4. **An issue follows the workflow of its type**: initial status, transitions available and the decision take the
   type (`Workflows` gains it in its calls; `issue` passes it).
5. **Changing the type of an issue**, or the workflow a type maps to: an issue whose status exists in its new workflow
   keeps it; otherwise it moves to the first status of the same category there (else to the initial one), in the same
   change, written in the changelog.
6. **Screens**: a table of the workflows of the project in `project-detail` (new as a copy, rename, remove) with the
   types each one serves; a screen `workflow-detail` (params `key`, `workflow`) with the tables of A for one workflow.

## Alternatives considered

- **A text form for the workflow** (`todo -> in_progress : start [guard]`, one line per transition, as the columns of
  a board are typed). Light to build, but the errors of a whole text are harder to place than those of one row, and a
  removal would need its own syntax for where the issues go.
- **Names for statuses outside the catalogues.** A status «In review» would read better than `in_review`, but every
  screen shows statuses through `valueKey` on the code; names per status in two languages are a change of their own.
- **Templates of the tenant** shared by projects. Useful with many similar projects; the copy of the default covers
  one project now, and a template can be added later without changing A or B.
- **Removing a status only when no issue is in it.** Simpler, but it forces moving issues one by one through
  transitions that may not exist; the move of point A.3 is one step, recorded.

## Consequences

- A: `workflow` API for statuses and transitions (create, update, delete), `version` on the workflow;
  `shared.IssueStatuses` implemented by `issue`; `agile` places statuses without a column; screens and texts; tests
  for the rules of A.2–A.4 and the move of A.3.
- B: migration (`is_default`, `workflow_scheme`, the unique constraint removed), `Workflows` calls with the type,
  `issue` on type changes, the screen `workflow-detail`; tests with two workflows in one project.
- ADR-10's «one workflow per project» is replaced by B once built.
