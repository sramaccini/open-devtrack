# OpenDevTrack screens

The front end of OpenDevTrack is this Quadrava project: screens, navigation, themes and translations, as JSON documents
validated by the schemas of the pinned Quadrava version (0.14.0, ADR-01). No Angular code: the runtime image of Quadrava
loads them from the registry of the back end (module `uiregistry`, ADR-03).

| Path | Registry kind | What |
| --- | --- | --- |
| `project.json` | (not published) | Identifier `opendevtrack`, languages and themes; the runtime gets them from its configuration |
| `navigation.json` | `navigation/main` | Areas and items of the side bar, with the permission each one requires |
| `screens/*.json` | `screen/<id>` | One screen per file |
| `datasources/*.json` | `datasources/<id>` | Data sources shared by screens: `projects` (with members, the workflows and custom field definitions), `workflows` (one workflow, its statuses and transitions), `users`, `issues` (with transitions, comments, attachments, history and custom fields) |
| `themes/*.json` | `theme/<id>` | `odt-light` (the default) and `odt-dark`, with the palette of [brand/](../brand/README.md) |
| `i18n/<lang>.json` | `messages/<lang>` | English and Italian texts, key → text; labels use `t('key', 'English default')` |

## Rules

- **Brand**: colours from `brand/README.md` (ink `#13233F`, teal text `#0F766E`, `HEAD` `#0D9488`/`#2DD4BF`, background
  `#F8FAFC`/`#0B1220`). The logos are the files of `brand/svg`, served next to the runtime under `odt-brand/`; the themes
  reference them, they are not copied here.
- **Permissions**: an area, item or screen that needs one says so with `requires` (`odt.<resource>.<action>`, ADR-07).
  Global permissions with `can()`; those of a project role with the `_perm` the back end puts on each row and form
  (`/authz/check` also answers them for a `project` or `issue` resource, A1, but `_perm` needs no extra request).
- **Texts**: every visible text goes through `t()` and has its key in both catalogues. The shell texts of the runtime
  (`app.*`) are built into it in English and Italian; the catalogues keep only `app.title` and the names of the
  themes (`app.theme.<id>`), and a shell key here would override the built-in text.
- **Field widths** (Quadrava 0.13.0): forms use `layout: "flow"` and each field a `width` by what it holds: `xs`
  short codes, keys and estimates; `s` dates, statuses, priorities, types; `m` names, users, choices; `l` titles and
  free text of one line. Descriptions, labels and lists of choices have none and take a line of their own; so do the
  templates of the custom fields (`fieldsFrom`), by type.
- **Validation**: `quadrava validate ui` (the Quadrava CLI, in CI) checks the documents as the runtime reads them:
  schemas, references to data sources, CEL expressions, themes and catalogues. In the back end, `SchemaValidationTest`
  checks them with the validator of the registry and `UiCataloguesTest` that every `t('key')` is in both catalogues.

## Publishing

The Quadrava CLI (`@sramaccini/quadrava-cli@0.14.0`, the version of the runtime) is on GitHub Packages: the `.npmrc`
of the repository maps `@sramaccini` there, and npm needs a token with `read:packages` for `npm.pkg.github.com` in
the user config (`~/.npmrc`; in CI, `NODE_AUTH_TOKEN`). From the root of the repository, with a Keycloak access token
of the client `opendevtrack-tools` in `QUADRAVA_TOKEN`:

```bash
npx --yes @sramaccini/quadrava-cli@0.14.0 validate ui
npx --yes @sramaccini/quadrava-cli@0.14.0 publish ui --registry http://localhost:28080 --app opendevtrack --env development
```

The same command publishes to `testing` and `production`: the registry has no protected environments (ADR-12), the
review is the one of the code before it reaches `main`.

`e2e/authz/run.sh up` publishes it to `development` as `mario.rossi` and starts the runtime on it
(http://localhost:28090).

## Comments and messages

The Comments tab of `issue-detail` is the `comments` thread of Quadrava: Markdown with `@` to pick a member
(`assignees`), posted through `issues/addComment`. The box shows to
those who may comment (`postIf` on `data.issue._perm.comment`, the answer the issue form loads; Quadrava 0.8.0, Q9). The author edits a comment in
place and deletes it, an admin of the project deletes those of others (`editIf`, `deleteIf` on the `_perm` of the
comment; Quadrava 0.10.0, Q8); an edited comment is marked as such (`editedField`). The bell of the shell reads `/api/notifications` (`notifications` in `navigation.json`, every
60 seconds) and opens the issue of a message.

## Labels

The labels of an issue are a `tags` field in `issue-new` and `issue-detail` (Quadrava 0.10.0, Q5), with the labels
already used in the project as suggestions (`issues/labels`); the back end keeps them in lower case and refuses
spaces. `issue-list` shows them in a column.

## Due date and reviewer

`issue-new` and `issue-detail` have «Reviewer» (a lookup of the members, as «Assignee») and «Due date» (a `date` field)
(ADR-19). `issue-list` and the results of `search` show «Due» and, past it and not done, a badge «Overdue» from the
field `overdue` of the rows: the `badges` of a Quadrava column colour by value, so the back end gives the value.

## Attachments

The Attachments tab of `issue-detail` uploads a file at once with a `file` field (`issues/uploadAttachment`, shown
when `data.issue._perm.attach`) and lists the attachments, with remove for whoever may. The name of a file downloads it
(`href` of the column, Quadrava 0.9.0, request Q10), and the field allows 25 MB, as the back end: the runtime image
forwards requests up to `QUADRAVA_MAX_BODY_SIZE` (100m by default, 26m in `e2e/authz/compose.yaml`; request Q11).

## Brand in the browser tab

The title of the tab is `brand.name` of the active theme and its icons are `brand.icons` (`svg`, `png32`,
`appleTouch`), the files of `brand/` served next to the runtime: `odt-brand/` (SVG) and `odt-brand-png/` (PNG), as
`e2e/authz/compose.yaml` mounts them. Until the theme is loaded the tab reads «Quadrava».

## Workflow

The Workflows section of `project-detail` (ADR-20, B) lists the workflows of the project (name, default, the issue
types each one serves, how many statuses and transitions), with New workflow among the buttons of the project (a copy
of the default), Open and Remove (only a workflow that serves no type, not the default). `workflow-detail` (params
`key`, `workflow`; data sources `workflows/*`) shows one: its name and the issue types it serves (read-only for the
default, which serves the types no other workflow maps), saved together; then a table of the statuses (position,
status, category, how many issues of its types) and one of the transitions (from, to, condition), with New status and
New transition among the buttons and Edit and Remove on each row, for an admin of the project (ADR-20, A). Removing a status asks where its issues go (a
lookup on the statuses). The rows carry the version of the workflow: a change made on an older copy answers 412 and
the screen says so. Statuses and transitions outside the catalogues are shown by their code (`values.status.*`,
`values.transition.*` translate only the built-in ones); categories are `values.category.*`.

## Boards and backlog

`board` is the generic screen of ADR-06 (params `board`): the `kanban` of Quadrava with the columns and the cards of
the back end (`columnsFrom`), one component whose lanes follow `data.board.lane`
(`laneField` and `lanes` as expressions, Q12). `backlog` (params `key`) is a `kanban` whose columns are the sprints not completed and the
backlog, with the sprints in a table below (new, start, complete, edit, remove). `board-list` (Projects › Boards) lists
the boards of the projects of the user; `project-detail` manages those of a project and `issue-detail` sets the story
points. A card opens its issue (`onOpen`) and its badges show the values in the language of the user (`valueKey`):
both from Q12, Quadrava 0.11.1. A board may have an ODQL filter (ADR-16), set in the dialogs of `project-detail`
and checked as one types (`search/check`), shown above the board when it has one.

## Search

`search` (Projects › Search, params `q`) is the ODQL search of ADR-16: a text field that suggests fields, operators
and values as one types (`suggest` from `/api/search/words`), the results in a `serverDataTable` with column filters
and groups computed by the back end, and the saved filters below (use, edit, remove; save the current query as a new
one). The field checks the query on the back end as one types and says where it is wrong; Enter runs it.
`issue-list` opens the search on its project («Open in search»).

## Dashboards

`dashboard` (Home › Dashboard, the first screen) is the personal dashboard of ADR-17: six indicators («Overdue» and «In review by me» from
ADR-19), the user's open
issues (`serverDataTable` on the search) and their filters marked «On my dashboard» with what each finds.
`project-dashboard` (params `key`, from the «Dashboard» button of `project-detail`): indicators, burndown, velocity,
cumulative flow and created vs resolved, on the `stats` and `chart` of Quadrava. The days are ISO dates with
`categoryFormat: "date"`: the chart leaves out the labels that do not fit and shortens them (Q14, Quadrava 0.12.0).
The sprint of the burndown is a `lookup` in an `input` bound to `state.sprint`, passed as `sprint` to `reports/burndown`
(which reloads when it changes); empty, the back end takes the active sprint, else the last one.

## Notification settings

`notification-settings` is the «Notifications» section of the settings dialog (`settings` in `navigation.json`, from
the user menu; Q13, Quadrava 0.12.0), the form of ADR-18: the address of the mails, read only, and for each kind a
switch (in the application) and a choice (by email: off, at once, in the daily summary), whose rows come from the back
end (`fieldsFrom` into `channels`). When the installation has no mail server it says so. The screen has no title: the
tab names it. `/?settings=notification-settings` opens the dialog on it (Q15, Quadrava 0.12.1), the link in the footer
of the mails; the query parameter `settings` belongs to the shell, so no screen takes one with that name.

## Webhooks

`webhook-list` (params `key`, from the «Webhooks» button of `project-detail`, shown to the admins of the project) and
`webhook-detail` (params `key` and `id`), on `datasources/webhooks.json` (ADR-21, B). The list has New, Edit, «Send a
test», «New secret» and Remove; the secret is shown once, in a dialog with a read-only field after the creation or
the rotation. The answer of a `call` with `as` stays in its own sequence, so the dialog of the creation passes the
secret to the next one through `state.secret`, emptied afterwards. `webhook-detail` shows the last 50 deliveries with
«Deliver again».

ADR-21 puts the webhooks in a section of `project-detail`. They are a screen of their own because the list answers
403 to whoever is not an admin of the project, and a data source of a screen loads for everybody who opens it: a
member would see the table fail. The value `*` (every event, those added later too) shows
as «All events»: a key of the catalogues cannot hold `*`, so `optionKeys` and `valueKeys` (Quadrava 0.14.0) give it
the key `values.webhook_event.all`.

## Administration

The area `admin` (ADR-22), shown to whoever holds `authz.rbac.manage` in the tenant of the session, on
`datasources/admin.json`, which calls the admin API of authz-library 2.2.0 (`/api/v1/admin`) as it is:

- `admin-users`: the users of the tenant and their roles. «Add user» creates the user (user name, email) and gives
  the roles; a user already known in another tenant answers 409, and the roles are given all the same. Row actions:
  Roles (replaces the roles of the user in the tenant), «Sign out everywhere» (`DELETE /bff/admin/users/{u}/sessions`,
  shown with `authz.session.revoke`), Turn off and Turn on (only for the global admin: authz-library changes a user
  only with `authz.rbac.manageglobal`).
- `admin-roles`: the global roles with their permissions; the global admin edits the description and the permissions
  of those that are not system roles.
- `admin-tenants` (`authz.rbac.manageglobal`): the tenants, and «New tenant».
- `admin-access-log`: the last 200 entries of the log of authz-library, with the actions and kinds translated.
- `admin-config-log` (`odt.project.admin`): the audit log of the administration of OpenDevTrack (ADR-22), the newest
  500, filtered by project, user, kind and days (inputs bound to `state`, so the table reloads by itself). The
  column «Changes» names the fields as the API does (`role`, `name`…): they are not translated.
- `project-log` (params `key`): the same log for one project, opened by the button «Administration log» of
  `project-detail`, shown to the admins of the project. A screen of its own and not a table in `project-detail`:
  every GET data source of a screen loads, so the members who are not admins would get a 403.
- `admin-groups` and `admin-group-detail` (params `id`) (`odt.project.admin`): the groups of the tenant (New, Edit,
  Remove, Open) and the members of one (Add member with a lookup on the users, Remove). A multiselect cannot list the
  users (the options of Quadrava are static), so members are added one at a time. A removal that would leave a project
  without an admin answers 409 and the toast says so.
- `admin-permission-schemes` (`odt.project.admin`, the admin of the projects of the tenant): the permission schemes of
  OpenDevTrack (`/api/permission-schemes`), New (a copy of the default), Edit (name, description, the permissions of
  each project role), Remove (not the default, not one in use). A refused change (400) says the rules: every role sees
  the issues, the admin always edits the project and manages its members.

Each item has its own `requires`; an area with no item to show is hidden, so the area shows to the admin of the users
and to the admin of the projects alike. In `project-detail` the form shows the scheme of the project, and «Change the
permission scheme» (for whoever edits the project) picks another one with a lookup on the schemes. It is a button of
its own under the form, with its own dialog, since it does not save the fields of the form; the actions of the form
wrap when they do not fit (Quadrava 0.14.0, Q17). The members table of `project-detail` shows through which groups a
user is a member, and a «Groups» table under it lists the groups of the project with Change role and Remove; «Add
group» is the button of that table.

Passwords and sign-in stay in Keycloak, and the users screen says so. ADR-22 asks for a link to the console of
Keycloak for the tenant: there is no address of the console in the configuration of the front end, so the text names
it without a link. The roles and permissions in the pickers are static lists (the options of Quadrava are strings):
the role of global admin is offered to the admin of a tenant too, and authz-library refuses it with 403. It refuses
with 403 a change to one's own roles too; the toast names both reasons, since the screen cannot tell them apart.
