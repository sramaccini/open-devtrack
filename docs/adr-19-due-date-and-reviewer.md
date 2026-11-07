# ADR-19: Due date and reviewer of an issue

Status: Proposed (7 November 2026) — F4, epic E8 (the indicators left out by ADR-17)

## Context

The plan sketches the personal dashboard with the indicators «Overdue» and «In review by me». ADR-17 point 5 left
them out: issues have neither a due date nor a reviewer, and it named the choice to make: a due date as a standard
field, or both as custom fields of a project (ADR-11).

What there is today:

- An issue has `type`, `summary`, `description`, `status`, `priority`, `assignee`, `reporter`, `labels` and the
  custom values of its project (`custom`, JSONB checked against definitions per project, ADR-11); `agile` keeps
  sprint, points and rank (ADR-15).
- The default workflow has three statuses, one per category (`todo`, `in_progress`, `done`); a project can rename and
  add statuses (ADR-10), so status names differ between projects. The guards of the transitions see `issue` as a map
  of its fields.
- `notification` writes a message `assigned` when the assignee changes (ADR-13), mailed according to the preferences
  per kind (ADR-18).
- `search` keeps one row per issue in `odt_search.issue_doc` with the fields ODQL knows (ADR-16); the personal
  counts of the dashboard are ODQL queries on it.

## Decision

1. **Two standard fields of the issue**, owned by `issue` (migration `V20`):
   - `due_on date`, optional: the day the issue should be done. A date, not a time: the rest of the reports work on
     days, in the zone `odt.reporting.zone`.
   - `reviewer varchar`, optional: the username of a member of the project, checked as the assignee is (a
     non-member is a 400). The reviewer may not be the assignee (a 400): a review by oneself is no review.
   - Both are edited with the other fields of the issue (`odt.issue.edit`), travel in `IssueCreated` and in the
     changes of `IssueChanged`, are written in the changelog by `audit`, and are in `issue` for the guards, so a
     project can ask for them with a CEL guard (`issue.reviewer != null` on a transition to its review status).
2. **ODQL** gains the fields `due` (a date, with the operators and the relative dates of the other dates: `due <
   today`, `due <= 7d`, `due IS EMPTY`) and `reviewer` (a user, with `currentUser()`, as `assignee`); `ORDER BY due`.
   `issue_doc` gains `due_on` and `reviewer`, filled by its listeners and by a reindex at the migration. Relative
   dates already go both ways (`7d` as well as `-7d`), so «due within a week» is `due <= 7d`.
3. **The two indicators**, counted in `GET /api/me/summary` and shown on `dashboard` (a `stats` of Quadrava, with no
   link: the same queries are a saved filter away), with these queries:
   - «Overdue»: `assignee = currentUser() AND due < today AND category != done`;
   - «In review by me»: `reviewer = currentUser() AND category = in_progress`. In progress, not «not done»: the
     reviewer is named early, the review starts when the work does; a project with a status of review in the
     `in_progress` category gets exactly its issues waiting for review. Narrower meanings stay a saved filter away.
4. **A message to the reviewer.** `notification` gains the kind `review_requested`, written when the reviewer is set
   or changed (as `assigned` for the assignee), with its preference (ADR-18: in the application and, by default,
   email at once). The reviewer is not made a watcher, as the assignee is not: whoever wants the changes
   that follow watches the issue (ADR-13). No reminders of due dates in this step: the digest of ADR-18 could list
   the issues due tomorrow later, as a change of its own.
5. **The screens.** `issue-detail` and the new issue dialog get «Due date» and «Reviewer» (a lookup of the members,
   as «Assignee»); `issue-list` and the `search` results get a «Due» column and, for the issues past it and not
   done, a badge «Overdue» in the danger tone (a field `overdue` of the rows: the `badges` of a Quadrava column
   colour by value, not by condition). Texts in English and Italian.

## Alternatives considered

- **Both as custom fields of each project (ADR-11).** Nothing new in `issue`, but every project would define them
  under its own key, the dashboard of a user with several projects could not count them, ODQL would need
  `custom.<key>` per project, and a reviewer field needs a user type that custom fields do not have.
- **A status «In review» in the default workflow** instead of a reviewer. «In review by me» needs to know who
  reviews, which a status does not say; and changing the built-in workflow touches only new projects (ADR-10). A
  project that wants the status adds it and, with the reviewer field, gets both.
- **Several reviewers.** Closer to code review tools, but a list of users is a new kind of field in issue, search
  and the screens, for an indicator that needs one name; to revisit if asked.
- **A due date with a time.** More precise for support-like work, but every report of the application is in days,
  and «Overdue» by the hour would differ between users in different zones.

## Consequences

- `issue`: migration `V20` (two columns, an index on `(project_id, due_on)`), entity, API, validation, events; tests
  for the member check, the reviewer not being the assignee, and the changelog.
- `search`: `issue_doc` columns and reindex, the fields `due` and `reviewer` in the compiler and in the suggestions,
  parser and compiler tests.
- `notification`: kind `review_requested` with its texts and preference.
- `search`: two more counts in `/api/me/summary`; `dashboard` with six indicators.
- `ui/`: fields and columns above, i18n keys in both catalogues.
- ADR-17 point 5 is closed by this ADR once built.
