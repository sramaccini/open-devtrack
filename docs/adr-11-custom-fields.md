# ADR-11: Custom fields of the issues

Status: Accepted (1 November 2026), on Quadrava 0.2.1 with `fieldsFrom` — F2 E2

## Context

The plan (E2) wants custom fields per project, and its data model already says how they are kept: a `custom` JSONB
column on the issue (`{"customer": "ACME", "severity": 2}`) with a GIN index, and `odt_issue.field_definition
(id, project_id, key, type, options, required_if)` with `required_if` in CEL; the back end validates the JSON before
saving it. It leaves open which types there are, who defines them, how they reach the changelog and the guards, and
how the screens show them.

A Quadrava screen is a JSON document reviewed before a protected environment (ADR-03), so a field that a project admin
adds at run time is in no screen. The first proposal of this ADR worked around it with a tab and a dialog per field;
on review the maintainer chose to wait for Quadrava instead (request Q6). Quadrava 0.2.0 added it (0.2.1 is the
release to use): a form node takes `fieldsFrom`, a data source with one row per field (name, label, type, options,
required), draws those fields after its own, keeps their values in an object of the form (`into`) and shows on them
the field errors the service returns (`/custom/<name>`). Labels and options are shown as they come, not translated;
rules that depend on other fields belong to the service.

## Decision

1. **Data**, as the plan says. `odt_issue.field_definition (id, project_id, key, label, type, options, required_if,
   position, archived)`, `key` unique per project (`^[a-z][a-z0-9_]{0,39}$`, at most 50 fields per project, the limit
   of `fieldsFrom`); `odt_issue.issue.custom jsonb NOT NULL DEFAULT '{}'` with a GIN index, for ODQL later (ADR-04).
2. **Types**, all drawn by Quadrava without a model except the last: `text` (at most 1000 characters), `number`,
   `date` (ISO date), `checkbox`, `select` (one of the options), `multiselect` (some of the options) and `user` (a
   member of the project, through the model `user` of `fieldsFrom.types`, a `lookup` over the members). Values are
   checked against the definitions when an issue is saved; a key without a definition is refused. Options are codes
   with a label; the key and the type of a field do not change once it exists, so the values saved stay valid.
3. **`required_if` in CEL**, on the variables of the guards (ADR-10: `issue`, `user`, `can()`), for example
   `issue.type == 'bug'`; empty for an optional field, `true` for one always required. The back end checks it on every
   save and answers 400 with the field errors of the Problem contract of Quadrava (`errors: [{path: "/custom/<key>",
   code: "customFieldRequired"}]`), which the form shows on the field with the text of `validation.customFieldRequired`.
   The rows of `fieldsFrom` say `required` when the rule is true for the issue as it is (or `true` on a new issue), so
   the form marks them before the save. The CEL engine of the guards moves from `workflow` to `shared`, one place that
   compiles and evaluates CEL; the custom values reach the guards as `issue.custom.<key>`, so a transition can ask for
   a field (`has(issue.custom.customer)`).
4. **Who**: the admins of the project (`odt.project.edit`) define the fields on `project-detail`; whoever edits an
   issue (`odt.issue.edit`) sets their values in `issue-new` and `issue-detail`. A field is archived, not deleted: it
   leaves the forms and the API, its values stay in the issues, and restoring it brings them back.
5. **Events and changelog**: a value that changes is a `FieldChange` named `custom.<key>`, with its values as text,
   so the history and the notifications see it like the other fields.
6. **Labels in one language.** Quadrava does not translate the labels and options of `fieldsFrom`: a project writes
   them in the language of its team.

## Alternatives considered

- **A column per field** (`ALTER TABLE` at run time). Real types and indexes, but DDL from the application and a
  schema that differs per tenant: against ADR-09 and Flyway.
- **Rows per value** (EAV). Easy to index per field, but every read joins and ODQL needs a join per condition; JSONB
  with GIN gives the same queries with one table.
- **Screens generated per project.** The form would be right without Quadrava changes, but `ui/` would no longer be
  the only source, and in a protected environment every change of a field would wait for a reviewer (ADR-03).
- **A Fields tab with an edit dialog until Q6** (the first proposal). Refused at the review: a temporary screen, a
  field could not be set when the issue is created, and a required one made the next save fail.

## Consequences

- One Flyway migration in `issue` (definitions and the `custom` column); `Guards` moves to `shared`, with its tests.
- The API of the issues takes and returns `custom` (an object of values); `GET /api/projects/{key}/fields` and
  `GET /api/issues/{key}/fields` give the rows of `fieldsFrom`.
- Labels and options of custom fields are not in the catalogues of `ui/`.
