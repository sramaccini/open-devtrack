# OpenDevTrack screens

The front end of OpenDevTrack is this Quadrava project: screens, navigation, themes and translations, as JSON documents
validated by the schemas of the pinned Quadrava version (0.1.0, ADR-01). No Angular code: the runtime image of Quadrava
loads them from the registry of the back end (module `uiregistry`, ADR-03).

| Path | Registry kind | What |
| --- | --- | --- |
| `project.json` | (not published) | Identifier `opendevtrack`, languages and themes; the runtime gets them from its configuration |
| `navigation.json` | `navigation/main` | Areas and items of the side bar, with the permission each one requires |
| `screens/*.json` | `screen/<id>` | One screen per file |
| `datasources/*.json` | `datasources/<id>` | Data sources shared by screens (none yet) |
| `themes/*.json` | `theme/<id>` | `odt-light` (the default) and `odt-dark`, with the palette of [brand/](../brand/README.md) |
| `i18n/<lang>.json` | `messages/<lang>` | English and Italian texts, key → text; labels use `t('key', 'English default')` |

## Rules

- **Brand**: colours from `brand/README.md` (ink `#13233F`, teal text `#0F766E`, `HEAD` `#0D9488`/`#2DD4BF`, background
  `#F8FAFC`/`#0B1220`). The logos are the files of `brand/svg`, served next to the runtime under `odt-brand/`; the themes
  reference them, they are not copied here.
- **Permissions**: an area, item or screen that needs one says so with `requires` (`odt.<resource>.<action>`, ADR-07).
- **Texts**: every visible text goes through `t()` and has its key in both catalogues. The catalogues also carry the
  shell texts of the runtime (`app.*`): with the registry the runtime takes them from the project (request Q4).
- **Validation**: `SchemaValidationTest` in the back end checks every document here with the validator of the registry.

## Publishing

Until the CLI of Quadrava 0.2.0 (`quadrava publish`, request Q2), `tools/publish-ui.mjs` saves and publishes each
document, with Keycloak access tokens of the client `opendevtrack-tools`:

```bash
node tools/publish-ui.mjs --registry http://localhost:28080 --env development --token <author token>
node tools/publish-ui.mjs --registry <url> --env production --token <author token> --reviewer-token <approver token>
```

`e2e/authz/run.sh up` publishes it to `development` as `mario.rossi` and starts the runtime on it
(http://localhost:28090). In a protected environment (`testing`, `production`) every version needs the approval of
someone other than its author.

## Served by the runtime 0.1.0, with two temporary workarounds

- The favicon and the page title: the image has no setting for them, so the e2e mounts the brand favicon over the icons
  of the image, and the tab still reads «Quadrava» (request Q3).
- The `app.*` texts in the catalogues (request Q4).
