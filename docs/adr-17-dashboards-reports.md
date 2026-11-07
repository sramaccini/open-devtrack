# ADR-17: Dashboards and reports

Status: Proposed (7 November 2026) — F4, epic E8

## Context

E8 of the project plan asks for dashboards, a personal one per user and one per project, with burndown, velocity,
cumulative flow and created vs resolved, read from data already aggregated by the module `reporting` (p95 < 300 ms).
The plan sketches the personal dashboard as indicators («Assigned to me», «Overdue», «In review by me», «Critical open
bugs»), three charts and a table of the user's issues. `reporting` exists but is empty.

What is there to build on:

- `issue` publishes `IssueCreated`, `IssueChanged` (each field from and to, status included) and `IssueDeleted`;
  `agile` publishes `SprintStarted` and `SprintCompleted` (with the number of issues done and carried) and keeps in
  `odt_agile.sprint_issue` what each sprint held (ADR-15); ADR-16 adds `IssuePlanned` (sprint, points, rank).
- The statuses of a workflow have a category, `todo`, `in_progress` or `done` (ADR-10); a project can rename and add
  statuses, so the statuses differ between projects.
- Quadrava has `stats` (indicators from the fields of a data source), `chart` (`line`, `bar`, stacked bars, a second
  axis; the series are listed in the screen) and the `serverDataTable`.

Two of the indicators of the plan have no data: issues have no due date («Overdue») and no reviewer («In review by
me»).

## Decision

1. **The read model of `reporting`**, fed by the events after the commit, as `audit` and `search`:
   - `odt_reporting.issue_state (issue_id, project_id, type, priority, status, category, assignee, points, sprint_id,
     created_on, started_on, resolved_on)`: `started_on` is the first day in an `in_progress` status, `resolved_on`
     the day it entered `done` (cleared if it leaves it);
   - `odt_reporting.daily_snapshot (project_id, sprint_id, day, todo, in_progress, done, remaining_points)`: one row
     per project and day (and per active sprint), written again at each event of the day and closed by a job each
     night, so a day with no event still has its row;
   - `odt_reporting.sprint_result (sprint_id, project_id, name, starts_on, ends_on, committed_points, done_points,
     carried_points, completed_at)`, from `SprintStarted` and `SprintCompleted`, which gain the points (committed at
     the start; done and carried at the end), so the numbers are those of `agile` at that moment.
   - Days are in one time zone for the instance (`odt.reporting.zone`, default UTC). A migration fills the tables from
     the existing data.
2. **Reports.** All read rows already computed, with jOOQ (ADR-04, without generated classes as ADR-16):
   - **Burndown** of a sprint: the remaining points day by day, and the ideal line from the committed points at the
     start to zero at the end;
   - **Velocity**: committed and done points of the last 6 completed sprints;
   - **Cumulative flow**: the issues per status category day by day, last 30 days. Categories, not statuses: the
     series of a Quadrava `chart` are fixed in the screen, and categories are the same in every project;
   - **Created vs resolved**: issues created and resolved per day, last 30 days;
   - **Cycle time**: the median of days from `started_on` to `resolved_on` of the issues resolved in the last 30 days.
3. **The API**: `GET /api/projects/{key}/reports/summary` (indicators), `…/burndown?sprint=` (the active sprint by
   default), `…/velocity`, `…/flow`, `…/created-resolved`; `GET /api/me/summary` for the personal dashboard. Project
   reports need `odt.project.browse`; the personal one counts only the projects the user can browse.
4. **Two dashboards, fixed layout.**
   - `dashboard`, the home of the application: indicators «Assigned to me» (open), «Reported by me» (open), «Critical
     bugs» (open bugs with priority highest in the user's projects) and «Resolved this week»; the table of the user's
     open issues (the `serverDataTable` of ADR-16 with `assignee = currentUser() AND category != done`); the saved
     filters of the user with the number of issues each finds (at most 10, the ones marked for the dashboard).
   - `project-dashboard`, opened from `project-detail`: indicators (open, in progress, resolved in 30 days, median
     cycle time), burndown of the active sprint (lines), velocity (bars), cumulative flow (stacked bars), created vs
     resolved (bars).
   - «Configurable dashboards» of the plan: for 1.0 the user configures which saved filters appear. Choosing and
     arranging widgets would need Quadrava to build components from data; not requested now, to see first whether
     the fixed layouts are enough.
5. **Indicators without data.** «Overdue» and «In review by me» are left out: they need a due date and a reviewer,
   which issues do not have. A due date as a standard field, or both as custom fields of a project (ADR-11), is a
   decision of its own, not taken here.

## Alternatives considered

- **Compute at each opening** from the tables of `issue` and `agile`: always exact, but slow on large projects and it
  reads other modules' schemas; the plan asks for rows ready to read.
- **Rebuild the history from `odt_audit.issue_change`** at each request: the changelog has every status change, but
  replaying it per day is the slow path above, and it belongs to `audit`. It is used once, by the migration that
  fills the tables.
- **Cumulative flow per status.** More detail, but the series of the chart would change with the workflow of each
  project; it needs a chart whose series come from data (a Quadrava request) for a gain that categories mostly give.
- **Dashboards made of widgets chosen by the user** now: the plan's word «configurable», but it needs a capability
  Quadrava does not have and a model of widgets to keep; fixed layouts plus saved filters first.

## Consequences

- Module `reporting`: migration `odt_reporting`, listeners, the nightly job, the API; the dependency on jOOQ shared
  with `search`.
- `agile`: `SprintStarted` and `SprintCompleted` carry points; `workflow` exposes the category of a status (as for
  ADR-16).
- Tests: the read model through a sequence of events (create, move, plan, sprint start and complete, reopen), the
  reports on known data, two tenants.
- `ui/`: screens `dashboard` (in place of the home placeholder) and `project-dashboard`, data sources `reports`; a
  «Dashboard» button on `project-detail`; «Show on dashboard» on saved filters.
