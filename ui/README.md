# OpenDevTrack screens

The front end of OpenDevTrack is this Quadrava project: screens, navigation, themes and translations, as JSON documents
validated by the schemas of the pinned Quadrava version (0.11.0, ADR-01). No Angular code: the runtime image of Quadrava
loads them from the registry of the back end (module `uiregistry`, ADR-03).

| Path | Registry kind | What |
| --- | --- | --- |
| `project.json` | (not published) | Identifier `opendevtrack`, languages and themes; the runtime gets them from its configuration |
| `navigation.json` | `navigation/main` | Areas and items of the side bar, with the permission each one requires |
| `screens/*.json` | `screen/<id>` | One screen per file |
| `datasources/*.json` | `datasources/<id>` | Data sources shared by screens: `projects` (with members, workflow and custom field definitions), `users`, `issues` (with transitions, comments, attachments, history and custom fields) |
| `themes/*.json` | `theme/<id>` | `odt-light` (the default) and `odt-dark`, with the palette of [brand/](../brand/README.md) |
| `i18n/<lang>.json` | `messages/<lang>` | English and Italian texts, key → text; labels use `t('key', 'English default')` |

## Rules

- **Brand**: colours from `brand/README.md` (ink `#13233F`, teal text `#0F766E`, `HEAD` `#0D9488`/`#2DD4BF`, background
  `#F8FAFC`/`#0B1220`). The logos are the files of `brand/svg`, served next to the runtime under `odt-brand/`; the themes
  reference them, they are not copied here.
- **Permissions**: an area, item or screen that needs one says so with `requires` (`odt.<resource>.<action>`, ADR-07).
  Global permissions with `can()`; those of a project role with the `_perm` the back end puts on each row and form
  (until authz-library A1).
- **Texts**: every visible text goes through `t()` and has its key in both catalogues. The shell texts of the runtime
  (`app.*`) are built into it in English and Italian; the catalogues keep only `app.title` and the names of the
  themes (`app.theme.<id>`), and a shell key here would override the built-in text.
- **Validation**: `quadrava validate ui` (the Quadrava CLI, in CI) checks the documents as the runtime reads them:
  schemas, references to data sources, CEL expressions, themes and catalogues. In the back end, `SchemaValidationTest`
  checks them with the validator of the registry and `UiCataloguesTest` that every `t('key')` is in both catalogues.

## Publishing

The Quadrava CLI (`@sramaccini/quadrava-cli@0.11.0`, the version of the runtime) is on GitHub Packages: the `.npmrc`
of the repository maps `@sramaccini` there, and npm needs a token with `read:packages` for `npm.pkg.github.com` in
the user config (`~/.npmrc`; in CI, `NODE_AUTH_TOKEN`). From the root of the repository, with a Keycloak access token
of the client `opendevtrack-tools` in `QUADRAVA_TOKEN`:

```bash
npx --yes @sramaccini/quadrava-cli@0.11.0 validate ui
npx --yes @sramaccini/quadrava-cli@0.11.0 publish ui --registry http://localhost:28080 --app opendevtrack --env development
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

## Attachments

The Attachments tab of `issue-detail` uploads a file at once with a `file` field (`issues/uploadAttachment`, shown
when `data.issue._perm.attach`) and lists the attachments, with remove for whoever may. The name of a file downloads it
(`href` of the column, Quadrava 0.9.0, request Q10), and the field allows 25 MB, as the back end: the runtime image
forwards requests up to `QUADRAVA_MAX_BODY_SIZE` (100m by default, 26m in `e2e/authz/compose.yaml`; request Q11).

## Brand in the browser tab

The title of the tab is `brand.name` of the active theme and its icons are `brand.icons` (`svg`, `png32`,
`appleTouch`), the files of `brand/` served next to the runtime: `odt-brand/` (SVG) and `odt-brand-png/` (PNG), as
`e2e/authz/compose.yaml` mounts them. Until the theme is loaded the tab reads «Quadrava».

## Boards and backlog

`board` is the generic screen of ADR-06 (params `board`): the `kanban` of Quadrava with the columns and the cards of
the back end (`columnsFrom`), one component for each kind of lanes (`laneField` and `lanes` are not expressions), shown
by `data.board.lane`. `backlog` (params `key`) is a `kanban` whose columns are the sprints not completed and the
backlog, with the sprints in a table below (new, start, complete, edit, remove). `board-list` (Projects › Boards) lists
the boards of the projects of the user; `project-detail` manages those of a project and `issue-detail` sets the story
points. A card does not open its issue yet, and its badges show the stored values: request Q12.
