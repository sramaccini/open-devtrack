# OpenDevTrack screens

The front end of OpenDevTrack is this Quadrava project: screens, navigation, themes and translations, as JSON documents
validated by the schemas of the pinned Quadrava version (0.2.1, ADR-01). No Angular code: the runtime image of Quadrava
loads them from the registry of the back end (module `uiregistry`, ADR-03).

| Path | Registry kind | What |
| --- | --- | --- |
| `project.json` | (not published) | Identifier `opendevtrack`, languages and themes; the runtime gets them from its configuration |
| `navigation.json` | `navigation/main` | Areas and items of the side bar, with the permission each one requires |
| `screens/*.json` | `screen/<id>` | One screen per file |
| `datasources/*.json` | `datasources/<id>` | Data sources shared by screens: `projects` (with members, workflow and custom field definitions), `users`, `issues` (with transitions, comments, history and custom fields) |
| `themes/*.json` | `theme/<id>` | `odt-light` (the default) and `odt-dark`, with the palette of [brand/](../brand/README.md) |
| `i18n/<lang>.json` | `messages/<lang>` | English and Italian texts, key → text; labels use `t('key', 'English default')` |

## Rules

- **Brand**: colours from `brand/README.md` (ink `#13233F`, teal text `#0F766E`, `HEAD` `#0D9488`/`#2DD4BF`, background
  `#F8FAFC`/`#0B1220`). The logos are the files of `brand/svg`, served next to the runtime under `odt-brand/`; the themes
  reference them, they are not copied here.
- **Permissions**: an area, item or screen that needs one says so with `requires` (`odt.<resource>.<action>`, ADR-07).
  Global permissions with `can()`; those of a project role with the `_perm` the back end puts on each row and form
  (until authz-library A1).
- **Texts**: every visible text goes through `t()` and has its key in both catalogues. The catalogues also carry the
  shell texts of the runtime (`app.*`): with the registry the runtime takes them from the project (request Q4).
- **Validation**: `quadrava validate ui` (the Quadrava CLI, in CI) checks the documents as the runtime reads them:
  schemas, references to data sources, CEL expressions, themes and catalogues. In the back end, `SchemaValidationTest`
  checks them with the validator of the registry and `UiCataloguesTest` that every `t('key')` is in both catalogues.

## Publishing

The Quadrava CLI (`@sramaccini/quadrava-cli@0.2.1`, the version of the runtime) is on GitHub Packages: the `.npmrc`
of the repository maps `@sramaccini` there, and npm needs a token with `read:packages` for `npm.pkg.github.com` in
the user config (`~/.npmrc`; in CI, `NODE_AUTH_TOKEN`). From the root of the repository, with a Keycloak access token
of the client `opendevtrack-tools` in `QUADRAVA_TOKEN`:

```bash
npx --yes @sramaccini/quadrava-cli@0.2.1 validate ui
npx --yes @sramaccini/quadrava-cli@0.2.1 publish ui --registry http://localhost:28080 --app opendevtrack --env development
```

A protected environment accepts approved versions only, and the CLI cannot request or approve a review yet (request
Q7): there `tools/publish-ui.mjs` publishes, as author, and approves with the token of someone else:

```bash
node tools/publish-ui.mjs --registry <url> --env production --token <author token> --reviewer-token <approver token>
```

`e2e/authz/run.sh up` publishes it to `development` as `mario.rossi` and starts the runtime on it
(http://localhost:28090). In a protected environment (`testing`, `production`) every version needs the approval of
someone other than its author.

## Served by the runtime 0.2.1, with two temporary workarounds

- The favicon and the page title: the image has no setting for them, so the e2e mounts the brand favicon over the icons
  of the image, and the tab still reads «Quadrava» (request Q3).
- The `app.*` texts in the catalogues (request Q4).
