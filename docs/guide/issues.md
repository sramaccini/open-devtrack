# Issues

An issue is one piece of work of a project: a task, a bug, a story or an epic. Its key is the key of the project and
a number (`WEB-4`), and it never changes.

## The list of a project

![The issues of a project](images/issue-list.png)

«Issues», from the list of projects or from a project, shows the issues of that project, the most recently updated
first. Sorting by a column is done from its header. «Late» shows «Overdue» for an issue past its due date and not
done.

The buttons:

- «New issue»: create an issue;
- «Backlog»: plan the issues in sprints ([Boards, backlog and sprints](boards.md));
- «Open in search»: the same issues in the search, where any condition can be added ([Search and ODQL](search.md));
- «Project and members»: the project.

## A new issue

![A new issue](images/issue-new.png)

Developers and admins of the project create issues. The fields:

| Field | What |
| --- | --- |
| Summary | One line: what it is about. Required |
| Type | Task, Bug, Story, Epic or Subtask ([below](#parent-and-subtasks)). Required |
| Parent | The epic of a story, task or bug, or the story, task or bug of a subtask, in the same project. Required for a subtask |
| Priority | Lowest, Low, Medium, High or Highest. Required |
| Assignee | The member who does it; «Assign to me» under the field, for developers and admins when the issue is not yours yet |
| Reviewer | The member who checks it: not the assignee |
| Start date | The day the work should start, as in Jira; never after the due date |
| Due date | The day it should be done. Past it, and not done, the issue is overdue |
| Labels | Words that group issues across types: lower-case letters, digits, dots, dashes and underscores. Enter or a comma adds one, and the labels already used in the project are suggested |
| Description | Text with simple formatting (Markdown), from the toolbar of the field: bold, italic, lists, links |

The custom fields of the project come after these ([below](#custom-fields)). A new issue starts in the first status
of its workflow ([Workflows](workflows.md)).

## An issue

![An issue: its summary, description, attachments, links and activity on the left, its details on the right](images/issue-detail.png)

The screen of an issue has two columns; on a phone the right one comes below the left one.

On the left is what the issue says and what happened to it:

- its **summary**, as a title, and its **description**, as text: developers and admins click one to change it, as in
  Jira. The summary is confirmed with ✓ or Enter, the description with «Save» or Ctrl+Enter; ✕, «Cancel» or Escape
  leave it as it was. Each is saved on its own;
- under the summary, **+** (Add), as in Jira: «Add subtask» («Add child work item» on an epic), «Attachment» and
  «Link work item», each for whoever may do it, without going down to their sections. «Link work item» opens the form
  of the links and brings it into view, the cursor in Issue;
- **Attachments**: the files of the issue ([below](#attachments));
- **Linked work items**: the other issues it is linked to; «Add linked work item», under them, adds one ([below](#links));
- **Activity**: its comments and its history in one list, as in Jira ([below](#history)).

The page has keyboard shortcuts, as in Jira; **?** lists those you may use. **E** edits the description, **A** opens
Assignee, **I** assigns the issue to you (not when you are its assignee or its reviewer), **L** opens Labels and **K**
opens the form of the links, the cursor in Issue. They are off while you type in a field or a window is open. **M**,
which puts the cursor in the comment box in Jira, comes with the next Quadrava
([#68](https://github.com/sramaccini/open-devtrack/issues/68)).

On the right is what describes it:

- the **status** button at the top, coloured by the category of the status (grey to do, blue in progress, green done).
  Its menu lists the statuses the issue can move to now, as badges: choosing one runs that transition. A transition
  with a condition that is not met is not listed. «View workflow» shows the workflow of the issue as a graph, with
  its statuses, the transitions between them and the current status outlined;
- next to it, a red flag while the issue is flagged (developers and admins remove the flag with it), then the eye
  with the number of people watching the issue, highlighted while you watch it: its menu
  has «Watch» or «Stop watching», and «Watchers», a window with who is watching it ([below](#watchers));
- the thumb with the number of votes, highlighted when you voted: it adds your vote or takes it back;
- the link: «Copy link» puts the address of the issue in the clipboard, to paste it anywhere;
- «Share»: up to ten members of the project, with an optional message; each gets a
  [notification](notifications.md);
- «…», more actions: «Add flag» or «Remove flag», «Add vote» or «Remove vote» ([below](#flag-and-votes)), «Clone», «Move» ([below](#move)),
  «Print», the three exports ([below](#export)), «All issues», and «Delete», for the admins of the project. «Clone» asks the summary of the copy («CLONE -»
  and this one) and opens it: it has the type, priority, assignee, reviewer, start and due dates, description, labels
  and custom fields of this issue, you as reporter, the first status of its workflow and the link «clones» to this one; comments,
  attachments, watchers, votes, the flag and the plan stay here. «Print» prints the page without menus and buttons. Delete cannot be undone, and the
  comments, attachments and links of the issue go with it;
- **Details**: its key, type, priority, assignee, reviewer, reporter, start and due dates, labels and custom fields;
- **Planning**: its story points (0 to 999, empty when not estimated) and the sprint it is planned in;
- under them, when it was **created** and last **updated**, as a time from now («3 days ago»); the date and time, in
  your time zone, are in the tooltip.

The fields of Details and Planning are changed in place, like the summary: a click opens one of them. A
choice from a list (type, priority, assignee, reviewer, sprint, a list or a user among the custom fields) is saved as
soon as it is picked; the other fields are saved with ✓ or Enter; ✕ or Escape cancels; a checkbox is saved when it is ticked.
The sprint is one not completed of the project, or the backlog, for whoever plans the sprints. The key only reads;
the reporter is changed by an admin of the project (a member of the project, as in Jira), and the new reporter watches
the issue.

Each field, or box, saves only itself, and shows the issue again as saved. If someone else saved the issue in the
meantime, saving says so: reload it and make the change again, so that no change is lost.

### Comments and mentions

Every member sees the comments, and the developers and admins write them. A comment takes the same simple formatting
as the description, and Ctrl+Enter publishes it.

Typing `@` offers the members of the project: a comment that names one with `@username` *mentions* them. They get a
message ([Notifications](notifications.md)) and start watching the issue. A name that is not a member stays plain
text, and so does an email address.

The author edits and deletes their comments. An edited comment is marked as such, and only the people mentioned for
the first time are told. The admins of the project also delete the comments of others.

### Attachments

Under the list of files, a dashed area with «Add attachment» uploads files at once, chosen with the button or dropped
on the area: up to 10 at a time, each with its progress and, when refused, its own reason; 25 MB per file and 100
files per issue. Clicking the name of a file downloads it. A picture (PNG, JPEG, GIF, WebP) shows small in the list;
«Preview» shows pictures and PDFs as large as the page, one after the other with ‹ and › (or the arrow keys), with a
button to download them.
Whoever attached a file removes it, and so do the admins of the project.

An installation may also set:

- a quota for the attachments of each project;
- an antivirus. A file in which it finds something is refused.

### Links

A link says how two issues are related. It is read from both ends. Every workspace starts with these types, and its
admin adds others, as «causes» / «is caused by», in Administration › Link types
([administrator guide](../admin/administration.md#link-types)):

| From one end | From the other |
| --- | --- |
| blocks | is blocked by |
| relates to | relates to |
| duplicates | is duplicated by |
| clones | is cloned by |

These four are shown in your language until the admin changes their descriptions; the others as the admin wrote them.
The links are grouped by relation, as in Jira: «blocks», «is blocked by» and so on. «Add linked work item» opens, in
the section, the relation and the other issue, by its key or by words of its summary; «Link» adds it, «Cancel» closes
it. The other issue may be in another project of the tenant. A link to an issue you cannot see is not shown.

### Watchers

The watchers of an issue are told of its comments and of the changes of its status. These people watch an issue
without asking:

- its reporter;
- each assignee, from the assignment;
- whoever is mentioned.

Anyone who sees the issue can start watching it or stop, from the menu of the eye. «Watchers», in the same menu, lists
who is watching it and since when; there developers and admins add a member with «Add watcher…», or remove one.

### Flag and votes

A flag marks an issue as impeded. Developers and admins add it with «Add flag» in the «…» menu: a panel under the menu then
asks why, to tell the team in a comment; closing it adds no comment. «Remove flag» takes it away. Both are in the history
of the issue, as «Flagged». A flagged issue stands out in yellow, with the flag, on boards, in the list of issues, in the backlog, in the
search results and on the dashboard, and `flagged IS NOT EMPTY` finds them all ([Search](search.md)).

A vote says that you want the issue done. Anyone who sees the issue votes for it once, with the thumb or «Add vote»,
and takes the vote back the same way or with «Remove vote». As in Jira, the reporter does not vote for their own issue,
and once the issue is done its votes no longer change: the thumb still shows them, but cannot be pressed.

### Export

Whoever sees an issue exports it from «…», as in Jira:

- **«Export Word (.docx)»** and **«Export OpenDocument (.odt)»**: a document to read or send, which opens in
  Microsoft Word, LibreOffice and the other office suites. It has the fields in a table, the description, the linked
  work items, the attachments listed and the comments, in your language. The description and the comments are their
  Markdown as written; times carry their zone (that of the reports, `ODT_REPORTING_ZONE`).
- **«Export JSON»**: everything about the issue for other tools: the fields, the custom fields with their names, the
  links, the watchers, the votes, the comments, the attachments listed and the whole history. Its field `schema` says
  the version of its shape.

The content of the attachments is not in the export: download them from the issue. Only the links to issues you see
are in it.

### Move

An admin of the project moves an issue to another project with «Move» in «…», as in Jira, if they can create issues
there. The dialog offers only those projects, then the type and, if you want, the status there. The issue takes the next key of that project (WEB-16 becomes MOB-4);
the old key keeps leading to it, in links, bookmarks, mentions and the search (`key = WEB-16`).

- Without a status chosen, it becomes the one with the same name in the workflow of the target, else the first of the
  same category, else its first status; the dialog proposes that one first.
- Custom fields that the target has with the same key and type keep their value; the others are lost.
- The assignee and the reviewer who are not members of the target are cleared.
- The issue leaves its sprint and goes to the bottom of the backlog of the target.

Before anything changes, the screen lists what would be lost and asks to confirm. Comments, attachments, history, links,
watchers, votes, labels, the flag and the story points follow the issue; the history records the move. Watchers who are
not members of the target stay in the list but get no notification while they cannot see the issue. One issue moves at
a time, never to another organisation. A story, task or bug moves with its subtasks, which take keys of the target
and keep leading to them by the old ones, and leaves its epic; an epic moves alone, and its children stay without an
epic. The confirm names both.

### Parent and subtasks

Issues have three levels, as in Jira: an **epic** groups stories, tasks and bugs, and each of these may have
**subtasks**, the steps of the work. «Parent» in the details of an issue is its epic, or the issue of a subtask.
Developers and admins change it: an epic for a story, task or bug (or none), a story,
task or bug for a subtask (always one), in the same project. The change is in the history, and the watchers of the
child are told as for any field.

Below the description, «Subtasks» (on an epic «Child work items») lists the children with their status and how many are
done; «Add subtask» (on an epic «Add child work item», a task) creates one with only its summary, and its page sets
the rest. A parent can be done while some children are open.

- An issue with children keeps its level: an epic stays an epic, a story with subtasks stays a story, task or bug.
- A subtask made a task leaves its parent; a task made a subtask needs one in the same change.
- A subtask has no story points: they go on its parent, so velocity and burndown count what the team commits to.
- The backlog lists the stories, tasks, bugs and epics; subtasks are on the page of their parent and on the boards.
- Deleting an issue deletes its subtasks, which the confirm names; the children of a deleted epic stay, without an epic.
- A subtask does not move to another project alone: move its parent ([above](#move)).

### History

Every change of a field is a row of the history: who made it, when, and the value before and after. So are its
parent, its links, its attachments and its flag.

Activity shows the comments and the history together, newest first, as in Jira: «changed Priority from Medium to
High», «set Assignee to anna.bianchi», «cleared Due date». **All**, **Comments** and **History** above it choose what
it shows, and **Newest first** turns the order. The box for a comment stays under every choice. The creation of the
issue and the values it was created with are not changes: they are in Created and in the fields.

## Custom fields

The admins of a project add fields of its own to its issues, in the tab **Custom fields** of the project («New
field»):

| Field | What |
| --- | --- |
| Key | Lower-case letters, digits and `_`, from a letter. It never changes, and the search uses it (`custom.customer`) |
| Label | What the forms show |
| Type | Text (up to 1000 characters), Number, Date, Check box, One choice, Several choices, or Member of the project. It never changes |
| Options | For One choice and Several choices: one per line, as `value` or `value: Label` |
| Required when | A condition, below. Empty for never, `true` for always |

A project has up to 50 custom fields. An archived field leaves the forms and keeps its values in the issues.

«Required when» is a condition in CEL on the issue and the user, the same language as the conditions of the
transitions ([Workflows](workflows.md#conditions)). For example:

```
issue.type == "bug"
issue.type == "bug" && issue.priority == "highest"
```

The condition is checked each time the issue is saved. A required field left empty is highlighted, and the issue is
not saved.
