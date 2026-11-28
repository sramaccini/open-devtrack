# ADR-30: Time tracking and work log, as in Jira

Status: Accepted (28 November 2026; decided by the maintainer, each proposal of A to D accepted) — F5

## Context

Jira tracks time on every work item with three values: the **original estimate**, the **remaining estimate** and the
**time spent**, the last being the sum of the **work log** entries (who, when the work started, how long, a comment).
The page of an item shows them as a bar in Time tracking, «Log work» opens a dialog (time spent, time remaining,
date started, comment), and the Activity has a «Work log» chip. Durations are written as `1w 2d 3h 30m`, and an
administrator sets how many hours make a day and how many days a week (8 and 5 by default). Who may log work, and
edit or delete the work logs of others, are permissions of the permission scheme. JQL has `originalEstimate`,
`remainingEstimate` and `timeSpent`, and the REST API answers both the text (`timeSpent: "3h 30m"`) and the seconds.

OpenDevTrack has none of it. It has the pieces around it: the Activity of an issue is the activity feed of Quadrava
0.34.0, which already draws rows of kind `worklog` («logged 2 hrs 30 mins») from `minutes` (T8, Q49); the
comments live in the module `collaboration`, which the module `audit` reads through `IssueComments` for the
Activity; the permission schemes of ADR-22 hold the project permissions of each role; every module that keeps data
about a person contributes to «Your data» through `PersonalData`. There is no table of settings per tenant. This is T9
of the plan towards Jira.

## Decision

1. **Minutes are the unit.** Every duration is stored as a whole number of minutes. The API reads and writes the text
   of Jira (`1w 2d 3h 30m`, any of the four parts, at least one minute, at most 10 000 hours) and answers the minutes
   as well: `timeSpent` and `timeSpentMinutes`, as the REST API of Jira does with seconds. Days and weeks become
   minutes with the hours per day and the days per week of the installation (point 6).
2. **The estimates are fields of the issue.** `odt_issue.issue` gets `original_estimate` and `remaining_estimate`
   (minutes, empty by default), changed by the `PATCH` of an issue with `odt.issue.edit` like the other fields, written
   to the history (`originalEstimate`, `remainingEstimate`), sent to the webhooks and in the export. The time spent is
   not stored on the issue: it is the sum of its work logs, kept in the search document (point 7).
3. **The work log is in `collaboration`**, next to the comments, which it resembles: `odt_collaboration.worklog` (id,
   issue, author, `started_at`, `minutes`, `comment` in the restricted Markdown of the comments, created and updated,
   version). `GET|POST /api/issues/{key}/worklogs`, `PUT|DELETE /api/issues/{key}/worklogs/{id}` (with the version
   read). A public `IssueWorkLogs` gives them to `audit` for the Activity, as `IssueComments` does (question A).
4. **The remaining estimate follows the work, as in Jira's default.** Logging time lowers the remaining estimate by it,
   never below zero, and changing or deleting a log gives back the difference; the dialog may instead set a new
   remaining estimate. «Leave it as it is» of Jira is not offered (question B).
5. **Permissions in the schemes.** Two new project permissions: `odt.worklog.add` (log work, and change or delete one's
   own logs: developer and admin) and `odt.worklog.manage` (change or delete those of others: admin). They join the
   sets of `ProjectRole`, and a migration adds them to the schemes that already exist, next to `odt.issue.comment` and
   `odt.issue.delete`.
6. **Hours per day and days per week are settings of the installation**, `odt.time-tracking.hours-per-day` (8) and
   `odt.time-tracking.days-per-week` (5), because there is no table of settings per tenant (question C). The UI reads
   them from `GET /api/time-tracking` to show durations the same way.
7. **Search.** The search document gets `original_estimate`, `remaining_estimate` and `time_spent` (minutes), and ODQL
   gets `originalEstimate`, `remainingEstimate` and `timeSpent`, compared with durations (`timeSpent > 2h`,
   `remainingEstimate IS EMPTY`), and the columns of the search table. This is a second step of the same task, since it
   changes the index (question D).
8. **The screens.** In Details, «Time tracking»: a `progress` bar of spent against spent plus remaining, with «3h
   logged» and «5h remaining» under it, and the original estimate; the estimates are fields of the inline form. «Log
   work» is an item of «…» and of the menu of Time tracking: a dialog with time spent, remaining (empty: lowered
   automatically), date started (now) and a comment. The Activity gets `workLog: { "minutesField": "minutes" }`, so
   the chip «Work log» appears; a work log in the feed has Edit and Delete by its `_perm`.
9. **Personal data and events.** A work log is personal data of its author: it is in «Your data» and is handled as a
   comment when a user is erased. `WorkLogged`, `WorkLogChanged` and `WorkLogDeleted` are events of `collaboration`;
   the webhooks get `worklog_created`, `worklog_updated` and `worklog_deleted`.

## Questions for the maintainer (each proposal accepted)

- **A. Where the work log lives.** Proposal: `collaboration`, with the comments (same permissions pattern, same Markdown,
  same reading by `audit`). Alternative: a new module `timetracking` with the estimates too, more separated but with a
  new module for two tables, and the estimates would leave the issue they belong to.
- **B. The remaining estimate when logging.** Proposal: lowered automatically, or set in the dialog (two of the four
  choices of Jira). Alternative: all four («leave», «reduce by»), more faithful and more fields in the dialog.
- **C. Settings of the installation or of the tenant.** Proposal: of the installation, in `application.yaml`.
  Alternative: per tenant, which needs a table of settings and a screen of the administration, for a value that is
  the same almost everywhere.
- **D. ODQL now or later.** Proposal: points 1–6 and 8–9 first, the search of point 7 in a second step, as for the
  links of ADR-29.

## Consequences

- Migrations: two columns of `odt_issue.issue`, the table `odt_collaboration.worklog`, the two permissions in the
  schemes; then three columns of `odt_search.issue_doc`, with a rebuild of the documents.
- `Activity` of `audit` gets the kind `worklog` (`minutes`, `text`, `started`); the Activity of the page sorts a work
  log by when it was logged, as Jira does.
- The guide of issues gets a section «Time tracking», the API guide the work logs, the administration guide the two
  settings; the webhooks list the three new events.
- Reporting by time (time spent per sprint or per person) is not part of this decision: it can be added on top of
  the work logs.

## Options considered

- **Time spent as a field the user edits.** Simpler, but there is no record of who worked when, and the «Work log»
  of the Activity would be empty. Rejected: Jira's model is the work log.
- **Hours as the unit (decimal).** Readable in the database, but rounding adds up across many logs. Rejected for
  whole minutes.
- **A rule engine or a plugin for time tracking.** Too heavy for three numbers and a table. Rejected.
