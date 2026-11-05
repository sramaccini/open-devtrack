# ADR-13: Mentions and messages for the user

Status: Accepted (5 November 2026; decided by the maintainer) — F2 E4

## Context

E4 wants mentions in the comments, and the module `notification` («in-app and email notifications, digests and
preferences») is still empty. The watchers of an issue (`odt_issue.watcher`) were added for it: the reporter, each
assignee and whoever chooses to watch.

Quadrava gives two pieces. The `comments` component writes a comment with an @ picker over a data source of people,
and passes the people still mentioned to its `onPost` steps; it shows the thread, but has no edit or delete of a
comment, which the Comments tab of `issue-detail` has today (a table with a dialog). Since 0.7.0 the navigation may
declare `notifications` (Quadrava ADR-22): a bell in the top bar fed by a GET of the application
(`{ id, title, text, date, read, screen, params }`), a POST that marks messages read, and optionally an event stream.
Titles and texts are shown as they come: the service writes them in the language of the user.

## Decision

1. **A mention is `@username` of a member of the project in the text of a comment.** The back end finds them when a
   comment is saved or edited (`@` at the start or after a space, then the username of a member; other `@` are text),
   whatever wrote the comment: the screen, the API, a tool. They are kept in `odt_collaboration.mention (comment_id,
   username)`, so an edit tells only the people newly mentioned. A mentioned member starts watching the issue.
   Mentioning someone who does not see the issue does nothing.
2. **The thread becomes the `comments` component** of Quadrava, with `mentions` over the members of the project
   (`@` then the first letters) and `onPost` on the API of the comments; the comment inserted reads `@mario.rossi`.
   Editing and deleting a comment need a request to Quadrava (**Q8**: actions on one comment of the `comments`
   component, as `rowActions` of a table). Until Q8 the Comments tab keeps today's table under the thread, for edit
   and delete only.
3. **Messages in the module `notification`**, from the events after the commit (Event Publication Registry, as the
   changelog): `odt_notification.message (id, project_id, recipient, kind, issue_key, actor, params, created_at,
   read_at)`: the listeners run without a session, so the tenant is the one of the project. The first kinds:
   - `mentioned`: someone mentioned you in a comment;
   - `assigned`: an issue was assigned to you;
   - `commented` and `transitioned`: a comment or a change of status on an issue you watch.
   Never to the user who did it. A message keeps its kind and values, not a text: the text is written when it is read,
   in the language of the request (`Accept-Language`, English or Italian), from a catalogue of the back end.
4. **The API of ADR-22.** `GET /api/notifications` gives the last 50 messages of the user in the tenant of the session
   (ADR-09: their projects are those of the tenant), newest first, each opening `issue-detail` of its issue; `POST /api/notifications/read` with `{ ids }`
   marks them read. A message whose issue the user no longer sees is left out. `ui/navigation.json` declares them, read
   every 60 seconds; the event stream (`live`) comes later, when the back end has one.
5. **Kept 90 days**, read or not, then deleted by a scheduled job. Email, digests and preferences are later steps of
   the same module.

## Alternatives considered

- **Mentions only from the `mentions` list of the component.** Simpler to read, but a comment written through the
  API, or edited, would mention nobody: the text is the only source every writer shares.
- **Keep the table and dialog for writing**, with `@username` typed by hand. Mentions would work, without the picker.
  The `comments` component is the piece made for it; the table stays only for what it cannot do yet (Q8).
- **Texts written when the message is created.** One language for every reader, fixed at that time.
- **A message per change for every watcher.** Too many: a status change, an assignment, a comment and a mention are
  what a watcher acts on; the other field changes stay in the history.

## Consequences

- Migrations in `collaboration` (mentions) and `notification` (messages); the module `notification` listens to the
  events of `issue` and `collaboration`, which gains an event for comments (`CommentPosted`, with the new mentions).
- `ui/`: `issue-detail` with the `comments` component and, until Q8, the table for edit and delete; `navigation.json`
  with `notifications`; catalogues of the back end for the texts of the messages (English and Italian).
- Request Q8 to Quadrava.
