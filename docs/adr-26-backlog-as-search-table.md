# ADR-26: The backlog as a search table

Status: Proposed (24 November 2026) — F5

## Context

ADR-15 made the backlog a `kanban`: one column per sprint not completed and one for the backlog, a drag to plan an
issue into a sprint or to change its place in the one order of the project. It rejected «the backlog as a table with a
«Move to sprint» action», because a table loses the order by drag.

In use, the maintainer finds the kanban a poor fit for planning. A column of cards shows little of each issue (no
reporter, due date, labels, status as a column), cannot be sorted or filtered, and grows into a long scroll as the
backlog grows; with ten columns side by side the sprints become narrow. What planning needs is what the issue search
already has (ADR-16): an ODQL query, a table with sortable and filterable columns, grouping and paging on the server.

What exists today:

- ODQL knows `sprint` (`=`, `!=`, `IN`, `IS [NOT] EMPTY`, `openSprints()`), `category` and `ORDER BY rank`. The index
  of the search holds `sprint_id` and `rank` of each issue.
- `POST /api/search` answers rows without the sprint: `sprint` is neither a column (`Searches.COLUMNS`) nor a group
  (`GROUPS`), and the rows carry no permission.
- The `serverDataTable` of Quadrava 0.18.0 has a source, sortable, filterable and groupable columns, `groupBy`,
  paging and row actions; it has no row selection and no bulk actions (the plain `table` has both).
- A move on a board already writes the one rank of the project (ADR-15, decision 1): the boards and the backlog never
  disagree on the order.

## Decision

1. **The `backlog` screen is a search.** On top, the query field of the search screen (suggestions and check of
   ADR-16), filled with
   `project = KEY AND (sprint IN openSprints() OR sprint IS EMPTY) AND category != done ORDER BY rank`: the issues of
   the active and planned sprints and of the backlog, in their order. The user changes it freely, as in the search;
   the screen does not add hidden conditions.
2. **A `serverDataTable` on `/api/search`, grouped by sprint.** The columns of the search (key, type, summary, status,
   priority, assignee, points, due) plus **Sprint**; the table opens grouped by sprint, so each sprint and the backlog
   are a group with its count, and the user can group by any other column or by none.
3. **Sprint in the search.** The rows of `/api/search` gain `sprint` (the name, empty in the backlog) and
   `sprintState`; `sprint` becomes a column (sortable: active, then planned by start date, then completed, then
   none; filter `select`) and a group, where the issues in no sprint form the group «Backlog». The names come from
   `Planning` of `agile`, which the search already uses for `openSprints()`.
4. **Row actions for planning.** On each row:
   - **Move to sprint…**: a dialog with the sprints not completed of the issue's project and the backlog (the
     existing `GET /api/projects/{key}/backlog/columns`), calling the existing move without neighbours;
   - **To the top** and **To the bottom**: the issue first or last in the order of its project. `MoveRequest` gains
     `to` (`top` or `bottom`), the back end finds the neighbour.

   The rows of the search gain `_perm` (`plan` for `odt.sprint.manage`, `rank` for `odt.issue.edit`, from the role of
   the user in the issue's project), so the actions show only to who can use them; the back end checks again, as
   today. The table refreshes after each action.
5. **The order by drag stays on the boards.** A scrum board (the active sprint) and the kanban board of a project (all
   its issues) already reorder by drag in the same rank. The backlog kanban is removed: its columns, cards and move
   endpoints stay only for as long as the screen needs them, then go with their tests and their OpenAPI.
6. **Sprints below, as today.** The table of the sprints (create, edit, start, complete, remove) stays under the
   issues, unchanged.
7. **Moving many issues at once** needs selection and bulk actions on the `serverDataTable`: requested from Quadrava as
   Q28. Until it is released, issues are planned one by one; with Q28, a bulk «Move to sprint…» on the selected
   rows.

## Alternatives considered

- **Keep the kanban, add a table view of it.** Two views of the same columns and cards: the table would still have no
  query, sort, filter or paging, which is what planning lacks.
- **A kanban whose lanes hold table rows.** Closer to the order by drag, but Quadrava has no such component; it would
  be a new component for one screen.
- **Keep the backlog kanban as a second tab next to the table.** It keeps the drag in the backlog, at the price of two
  screens to maintain and two ways to do the same thing; the boards already reorder by drag.
- **Rank by «Move up» and «Move down» row actions.** One step at a time is slow on a long backlog, and the row does
  not know its neighbours on another page; top and bottom, plus the drag on the boards, cover the common cases.
- **A backlog endpoint of its own in `agile`** with the planning rows. It would repeat the query, the sort, the
  filters and the paging of the search; adding the sprint to the search serves the issue search too.

## Consequences

- `search`: `sprint` and `sprintState` in the rows, `sprint` in `COLUMNS` and `GROUPS`, `_perm` on the rows; tests and
  `docs/openapi.json`.
- `agile`: `to` (`top`, `bottom`) in the move of the backlog; later the removal of the backlog columns and cards.
- `ui/`: `backlog.json` rewritten (query, table, row actions, sprints), texts in English and Italian; the guide
  (`docs/guide/`) and its images updated.
- ADR-15, decision 6 and the alternative it rejected, are superseded for the backlog by this ADR; the rest of ADR-15
  (rank, sprints, boards, permissions) is unchanged.
- Q28 in `docs/README.md` and a GitHub issue, sent to the Quadrava session.
- The order inside a sprint is changed on the boards or with top and bottom: a planner who wants to drag in the
  backlog itself loses that, until a table of Quadrava can reorder rows by drag.
