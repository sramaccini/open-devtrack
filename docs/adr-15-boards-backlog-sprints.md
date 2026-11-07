# ADR-15: Boards, backlog and sprints

Status: Accepted (6 November 2026; decided by the maintainer) — F3

## Context

F3 brings the boards, the backlog and the sprints into the module `agile`, which is still empty. ADR-06 already
decided the board screen: one generic `board` screen, with columns and cards from the back end (`columnsFrom` of the
Quadrava `kanban`). Still open: what a board is, how the issues are ordered, what a sprint is and how the backlog is
shown.

Quadrava 0.10.0 gives the `kanban` with columns from data, `reorder` (a card dropped between two others, with the keys
of its neighbours in `beforeId` and `afterId`), swimlanes (`laneField`), WIP limits and `moveIf`. It has no
reorderable list, but a `kanban` whose columns are the sprints and the backlog is one: a drag plans an issue into a
sprint, and inside a column it changes the order.

The workflow of a project (ADR-10) has statuses with a category (`todo`, `in_progress`, `done`) and transitions with
CEL guards; only a transition changes the status of an issue.

## Decision

1. **One order per project, the rank.** Each issue has a rank in `odt_agile.issue_plan (issue_id, project_id, rank,
   sprint_id, points)`: a text in fractional indexing (base 62), so a move between two neighbours writes only the
   moved issue, with a key between theirs. The backlog, the sprints and the boards all show the issues in this order.
   A new issue goes to the bottom, from the `IssueCreated` event; a deleted one leaves with `IssueDeleted`. Story
   points (`points`, an optional number from 0 to 999) are kept with the plan.
2. **Sprints.** `odt_agile.sprint (id, project_id, name, goal, state, starts_on, ends_on, completed_at)`, with the
   states `planned`, `active` and `completed`; at most one active sprint per project. An issue is in at most one sprint
   that is not completed (`sprint_id`), otherwise in the backlog. Completing a sprint moves its issues not in a `done`
   status to the backlog or to a planned sprint; what each sprint held when it started and when it completed is kept
   in `odt_agile.sprint_issue (sprint_id, issue_id, added_at, removed_at, outcome)` for the reports of F4.
3. **Boards.** `odt_agile.board (id, project_id, name, kind, lane)` with `board_column (board_id, position, name,
   statuses, wip_limit)`: a column holds one or more statuses of the workflow. `kind` is `scrum` (the issues of the
   active sprint) or `kanban` (every issue of the project, without those `done` for more than 14 days); `lane` is
   `none`, `assignee`, `priority` or `type`. Each project gets a board `kanban` when it is created, without columns of its
   own: such a board has one column per status of the workflow and follows it as it changes. The admins of the project
   add, change and remove boards; the columns are written one per line, `Name: status, status | limit`. A move to another column runs a transition of
   the workflow to a status of that column: the first allowed in the order of the workflow; none allowed answers 409
   and the card goes back. The guards of ADR-10 decide, as on `issue-detail`.
4. **The API of ADR-06.** `GET /api/boards/{id}/columns` (`value`, `label`, `limit`) and `GET /api/boards/{id}/cards`
   (key, title, column, lane, badges, points and `_perm`), with `POST /api/boards/{id}/cards/{key}/move` taking the
   column and `beforeId`/`afterId`. The backlog has the same shape: `GET /api/projects/{key}/backlog/columns` (the
   active sprint, the planned ones and the backlog, at most 10) and `…/backlog/cards`, with a move that changes the
   sprint and the rank.
5. **Permissions** (ADR-07): `odt.issue.edit` (developer) ranks issues and sets points; `odt.sprint.manage` (new,
   developer and admin) creates, starts and completes sprints and plans issues into them; `odt.project.edit` (admin)
   manages the boards. Moving a card to another column needs `odt.issue.transition`, as a transition.
6. **Screens.** A generic `board` screen (ADR-06), opened from `project-detail` with the board in `params`; a
   `backlog` screen with the `kanban` of sprints and backlog, with buttons to create, start and complete a sprint
   (dialogs); points on the cards and in a small form on `issue-detail`. A board's columns are edited on
   `project-detail`.

## Alternatives considered

- **A whole number as rank, renumbered on each move.** Simple to read, but a move rewrites every issue between the
  two places; with thousands of issues it is a large write and a frequent conflict.
- **A rank per board.** Each board in its own order: more freedom, but the backlog and the boards stop agreeing, and
  each board needs its own rank of every issue.
- **The backlog as a table with a «Move to sprint» action.** It needs no new idea, but loses the order by drag, the
  most frequent gesture of planning. A `kanban` of sprints does both with what Quadrava already has.
- **Several active sprints per project.** Useful for parallel teams; it complicates the scrum board and the reports.
  One active sprint for 1.0; teams that need more use separate projects.
- **Points as a custom field (ADR-11).** They would need a type, a definition per project and a guard to be summed;
  points are part of planning, and the reports need them in a known place.

## Consequences

- Migrations of `odt_agile` (plan, sprints, sprint history, boards and columns); the permission `odt.sprint.manage` in
  the catalogue and in the roles developer and admin.
- `agile` listens to `IssueCreated` and `IssueDeleted`, and uses `Workflows` of `workflow` for the statuses and the
  transitions of a move; it publishes events for the sprints (started, completed) for the reports of F4.
- `ui/`: screens `board` and `backlog`, the boards on `project-detail`, points on `issue-detail`; items in the
  navigation.
- The `kanban` of Quadrava 0.10.0 covers columns from data, order, swimlanes and WIP limits. Found in the build: a card
  cannot open its issue, and the badges show the stored values (`bug`, `high`) without a catalogue key; requested as
  Q12 (released in Quadrava 0.11.1 and adopted, with `laneField` and `lanes` as expressions). A moved card takes its place just after the card above it in the one order of the project (or just before the
  one below, at the top of a column), so a board and the backlog never disagree.
