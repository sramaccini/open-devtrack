# ADR-27: Moving an issue to another project

Status: Accepted (27 November 2026; decided by the maintainer) — F5

## Context

Jira has «Move» in the «…» of an issue: the issue goes to another project (or another type), and a wizard maps what
does not fit there: the status, when the workflows differ; the fields the target project does not have. The issue
gets a key of the target project (WEB-3 becomes APP-41), and the old key keeps working: links, bookmarks and mentions
with WEB-3 lead to APP-41. Jira asks «Move Issues» in the source project and «Create Issues» in the target.

In OpenDevTrack a change of **type** within the project already exists: it is a field of the issue, and a type with
another workflow moves the status there in the same change (ADR-20, B.5). What is missing is the move to another
**project**, which touches more than one module, because much of an issue hangs on its project:

- `issue`: the key is the key of the project and the number of the issue in it (`odt_issue.issue`, unique per
  project, numbered by the counter of the project); the custom fields are defined per project (ADR-11); the
  assignee and the reviewer must be members of the project; watchers and votes are of users who see the issue.
- `workflow`: the status is a status of the workflow of the project for the type (ADR-20).
- `agile`: the sprint and the rank are of the project (`odt_agile.issue_plan`, one order per project, ADR-15).
- `search`: the index holds project and key (`odt_search.issue_doc`); ODQL finds `key = WEB-3`.
- `collaboration`, `audit`: comments, attachments and the history are keyed by the issue id; they can follow it.
- `notification`, `integration`: messages and webhook deliveries carry the project and the key at the time.
- `reporting`: the state of the issue and the daily snapshots are per project.

## Decision

1. **Move is a project change of one issue, with its own endpoint.** `POST /api/issues/{key}/move` with `project`
   (the key of the target), `type`, `status` (a status of the workflow of the target for that type), `version`, and
   `fields` for the mapping (below). It is not a field of the `PATCH`: it changes the key and the module of the
   rules, so it is one step with its own checks and its own event. The answer is the issue with its new key.
2. **Permissions: a new project permission `odt.issue.move` in the source, `odt.issue.create` in the target.** As
   in Jira. `odt.issue.move` joins the catalogue for the role admin in every scheme (as `odt.reporter.edit` did); a
   scheme may give it to developers. A move to a project the user does not see answers 404, as for any project.
3. **The new key, and the old one keeps working.** The issue takes the next number of the target. Its old key is
   written in `odt_issue.moved_key` (`tenant_id`, `project_key`, `number`, `issue_id`): every lookup by key
   (`Issues.find`, the API, the screens, ODQL `key =`) that does not find the key looks there and answers the issue
   as it is now. The number of the source is never given again (the counter of the source does not go back). A second move adds a row; all old keys lead to the issue.
4. **What does not fit is mapped in the request, nothing is dropped silently.** The screen asks only what is needed:
   - the **status**: a status of the target workflow; the default is the one with the same code, else the first of
     the same category, else the first status of the workflow;
   - the **custom fields**: a field of the source with the same key and type in the target keeps its value; any
     other value is dropped, and the request must list it in `fields.drop` (a 400 names the values that would be lost,
     so the screen can show them first);
   - the **assignee** and the **reviewer**, when they are not members of the target: cleared, named in the answer;
   - the **sprint and the rank**: the issue leaves its sprint and goes to the bottom of the backlog of the target.
5. **What follows the issue.** Comments, attachments, history, links, watchers, votes, labels, flag and story
   points stay with the issue (they are keyed by its id). Watchers who do not see the target project stay in the
   list but get no message while they cannot see it, as for any watcher who loses access. The history gets the
   changes of project, key, type, status and each dropped field, by the user who moved it.
6. **One event, read by each module.** `IssueMoved(issueId, fromProject, toProject, fromKey, toKey, by, at,
   changes)` in `IssueEvents`: `search` indexes the issue again, `agile` writes the new plan, `reporting` moves the
   state of the issue (the snapshots of the past stay with the source: they were true on their day), `notification`
   tells the watchers who see the target, `integration` sends `issue.moved` to the webhooks of **both** projects
   (the source learns the new key). The messages and deliveries of the past keep the key they had.
7. **One issue at a time.** A bulk move waits for the bulk actions of the search (Q28) and a later decision; so do
   the sub-tasks and the children of an epic (T5): until T5, an epic moves alone and its children stay.
8. **The screen.** «Move» in the «…» of the issue (with `_perm.move`), a `dialog` with the target project, the type
   and the status; on a 400 listing the values that would be lost, a second dialog lists them and asks to confirm.
   After the move the screen opens the new key.

## Consequences

- `Issues.find` gains a second lookup; every module that resolves a key goes through it, so no module reads
  `odt_issue.issue` by project and number on its own (a check for the architecture test).
- Links in Markdown and mentions with the old key keep working through the lookup; the text is not rewritten.
- The public API gains an endpoint and a webhook event (`issue.moved`); the guides of issues, projects, workflows,
  webhooks and the API say what moves and what does not.
- An issue moved away from a project leaves a hole in its numbers, as in Jira.
- The new permission needs a migration that adds it to the role admin of every scheme, as V36 did.

## Options considered

- **Keep the key, change only the project.** No redirect needed, but `WEB-3` in project APP breaks the rule that the
  key says the project, and a later `WEB-3` could not exist. Rejected.
- **Copy and delete (clone into the target, then delete the source).** Simple, but the issue loses its id: comments,
  history, votes and links would have to be copied, and references by id from other modules break. Rejected.
- **Move as a field of the `PATCH`.** One endpoint less, but a change of key and of the rules of every field in one
  inline save of a form, with no place for the mapping. Rejected.
- **A full Jira wizard (several steps, bulk).** Heavy for one issue; the two dialogs cover the same choices. Later,
  with the bulk actions.

## Decided by the maintainer

- `odt.issue.move` is a new project permission, as in point 2, not `odt.issue.delete`.
- Watchers who do not see the target stay in the list and get no message while they cannot see it, as in point 5.
- A move to a project of another tenant is never possible: tenants are separate (ADR-09).
