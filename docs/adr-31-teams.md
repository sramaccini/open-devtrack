# ADR-31: Teams, and the Team of an issue

Status: Accepted (28 November 2026; decided by the maintainer, each proposal of A to C accepted) — F5

## Context

Jira has a **Team** field on work items: a team is a named set of people (with a lead or owner), shared across
projects, and the field says which team does the work. Boards and filters use it (`team = "Platform"`), and the
plans group the work by it. Jira keeps teams apart from **groups**, which give permissions: adding a person to a team
gives them no access.

OpenDevTrack has groups (ADR-22, `odt_access.user_group`): named sets of users of a tenant, managed by the admin of
the tenant, which give a role in a project when the group is added to its members. Membership of a group alone gives
nothing: only the projects the group is a member of. There is no field on the issue that names a set of people. The
start date, the other half of T10 of the plan towards Jira, is done (b68a700); the team waits for this decision.

## Decision

1. **A team is its own entity, not a group** (question A). `odt_access.team` (id, tenant, name unique in the tenant,
   description, lead, created, version) and `odt_access.team_member` (team, username). It lives in `access`, next to the
   groups, since both are sets of users of the tenant, but gives no permission: it only names who does the work.
2. **Who manages teams** (question B): the admin of the tenant creates, renames and deletes them; the lead of a team
   manages its members. `GET /api/teams?q=` for every user who sees a project (for the field and its lookup),
   `POST /api/teams`, `PUT|DELETE /api/teams/{id}`, `GET|POST|DELETE /api/teams/{id}/members`. A screen «Teams» in the
   administration, as «Groups».
3. **The Team of an issue.** `odt_issue.issue` gets `team_id` (empty by default, no foreign key across modules), set
   by the `PATCH` of an issue with `odt.issue.edit` and on a new issue, written to the history by the name of the team,
   sent to the webhooks and in the export. Any team of the tenant can be chosen, not only those whose members are in
   the project (question C).
4. **A team deleted** leaves the issues without a team: `access` publishes `TeamDeleted`, `issue` clears the field
   (with a row of history), as the links of a deleted issue are removed.
5. **Search.** The search document gets the team, ODQL `team = "Platform"`, `team IN (...)`, `team IS EMPTY`, and the
   column and group «Team» of the search table and of the backlog; a board filters by team with its ODQL filter.
6. **The screens.** «Team» in Details, a lookup on `GET /api/teams`, after Reviewer; in «Create issue» too. The admin
   screen «Teams» lists them with lead and members.
7. **Personal data.** Being a member or the lead of a team is personal data: it is in «Your data», and erasing a user
   removes them from the teams (a team whose lead is erased has no lead until one is set).

## Questions for the maintainer (each proposal accepted)

- **A. Team or group.** Proposal: a new entity, as in Jira, so that putting a person in a team never gives access.
  Alternative: a group marked as a team (a flag on `user_group`): no new tables and one place for sets of people, but
  every team is also something that can give roles, and a group made for access shows up as a team only if flagged.
- **B. Who manages teams.** Proposal: the admin of the tenant creates them and the lead manages the members.
  Alternative: anyone creates a team and becomes its lead, as in Atlassian, more open and harder to keep tidy.
- **C. Which teams an issue may name.** Proposal: any team of the tenant. Alternative: only teams with a member in
  the project, which needs the membership of the project in every choice.

## Consequences

- Migrations: two tables of `access`, a column of `odt_issue.issue`, a column of `odt_search.issue_doc` with a rebuild
  of the documents.
- The guides of issues, search and administration, the API guide and the webhooks change; the field appears in the
  export.
- The plans of Jira (grouping the work of many projects by team) are not part of this decision.

## Options considered

- **The Team as a custom field of type select.** No new code, but the options are per project, without members or
  lead, and ODQL would compare texts. Rejected: a team is shared across projects and is made of people.
- **Teams per project.** Simpler permissions, but a team works on several projects, as in Jira. Rejected.
