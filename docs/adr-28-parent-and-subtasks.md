# ADR-28: Parent and subtasks

Status: Proposed (27 November 2026) — F5

## Context

Jira has a hierarchy of work items: an **epic** is the parent of stories, tasks and bugs, and each of these may have
**subtasks**. The page of an issue shows the parent in the breadcrumb and in a «Parent» field, and a section with the
children («Subtasks», «Child work items» on an epic) with their progress and an «Add subtask» that creates one in
place. ODQL finds them with `parent = WEB-1`; the backlog lists the standard items and the subtasks under them; boards
show subtasks as cards.

OpenDevTrack has the type `epic` but no relation between an epic and its issues, and no subtasks: `odt_issue.issue`
has no parent, and the links (`blocks`, `relates`, `duplicates`, `clones`) are relations between peers, not a
hierarchy. ADR-27 left the children of an epic and the subtasks to this decision (its point 7): until then an epic
moves alone. This is T5 of the plan towards Jira.

The workflow of an issue already depends on its type (the workflow scheme of a project maps a type to a workflow,
with a default), so a new type needs no change there.

## Decision

1. **Three levels, fixed.** Level 1: `epic`. Level 0: `story`, `task`, `bug`. Level −1: a new type **`subtask`**.
   The parent of a level-0 issue is an epic; the parent of a subtask is a level-0 issue; an epic has no parent; a
   subtask always has one. The levels and the types are not configurable (configurable types and levels would come
   with the field layouts of T14).
2. **The parent is a field of the child.** `odt_issue.issue.parent_id` (nullable, same tenant, the parent must exist
   and keep the rule of point 1). The API gives `parent` (key, summary, type, status) in the issue, and takes
   `parent` (a key, or null to remove it) in `POST /api/issues` and in the `PATCH` of an issue; a change of type and
   parent that only make sense together go in the same `PATCH`. A cycle is impossible by point 1.
3. **Permissions: no new one.** Setting or removing the parent is an edit of the child (`odt.issue.edit` there) and
   needs the parent to be visible to the user; creating a subtask is `odt.issue.create` in its project. The parent
   gives no rights on its children, and the children none on their parent.
4. **The children.** `GET /api/issues/{key}/children`: the children in rank order with key, summary, type, status,
   category, assignee and priority, and the progress (how many in each status category). The page of the issue shows
   them in a section «Subtasks» (on an epic «Child work items») with a progress bar and «Add subtask» (or «Add child
   work item»), which creates one in place with only the summary, as Jira does.
5. **History, events, search.** The parent is a field of the history («Parent»), changed through `IssueChanged`
   like the others, so webhooks (`issue.updated`), notifications to the watchers of the child and the audit follow
   with no new event. The search document gets `parent_key`, and ODQL gets `parent = WEB-1`, `parent IN (...)`,
   `parent IS EMPTY` and the type `subtask`. An old key of a moved parent finds it, as in ADR-27.
6. **Agile and reporting.** Story points stay on level-0 issues: a subtask has none (the field is refused on it), so
   velocity and burndown count the items the team commits to, as in Jira. Boards show subtasks as cards in their
   columns. What the backlog lists is question B.
7. **The open questions of the maintainer** (A to E below) decide the cross-project parent, deletes, the done
   parent with open children, the backlog and the move.

## Questions for the maintainer

- **A. Parent in another project.** Jira allows the children of an epic in other projects (company-managed), and
  keeps subtasks in the project of their parent. Proposal: subtasks always in the project of their parent; the
  children of an epic **in the same project only** for now (simpler permissions and moves), a cross-project epic
  later if needed.
- **B. Subtasks in the backlog.** Proposal: the backlog shows level-0 issues and epics only; the subtasks show on
  the page of their parent and on the boards. Alternative: they stay in the backlog as rows of their own.
- **C. Delete.** Jira deletes the subtasks with their parent, after a warning that names them, and leaves the
  children of an epic without a parent. Proposal: the same, with the confirm of the delete listing the subtasks.
- **D. A parent done while children are open.** Jira allows it by default. Proposal: allowed; the section shows the
  open children and the progress; a workflow condition that refuses it can come later with the rules of T20.
- **E. Move (ADR-27 point 7).** Proposal: a subtask does not move alone to another project (it would lose its parent:
  the screen offers to move its parent); a level-0 issue moves **with its subtasks**, which take keys of the target;
  an epic moves alone and its children lose their parent (by A they cannot stay in another project), which the dry
  run of the move lists before the confirm.

## Consequences

- A migration adds `parent_id` (with an index, `ON DELETE` handled by the module, not by the database, so that the
  events are written) and the type `subtask` to the check of `type`; the search index adds `parent_key`.
- The workflow schemes, the screens of types and the values of the UI gain `subtask` (a scheme without it uses its
  default workflow).
- `IssueEditor` checks the rules of point 1 on create, on the `PATCH` of parent or type, and on a move.
- The guides of issues, boards and search, the API and the webhooks say what the parent is and does.
- The export (Word, OpenDocument, JSON) gives the parent and the children.

## Options considered

- **Epic Link as a link type.** Jira had «Epic Link» and «Parent link» as separate fields until 2023 and unified them
  as `parent`. A link type would allow cycles and many parents. Rejected.
- **Configurable levels now.** More general, but it needs field layouts per project and type (T14) to say which
  fields each level has. Later.
- **Subtasks as checklists in the description.** No status, assignee or history. Rejected.
