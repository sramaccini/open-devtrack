# ADR-11: Custom fields of the issues

Status: Proposed (1 November 2026) — F2 E2

## Context

The plan (E2) wants custom fields per project, and its data model already says how they are kept: a `custom` JSONB
column on the issue (`{"customer": "ACME", "severity": 2}`) with a GIN index, and `odt_issue.field_definition
(id, project_id, key, type, options, required_if)` with `required_if` in CEL; the back end validates the JSON before
saving it. It leaves open which types there are, who defines them, how they reach the changelog and the guards, and
above all how the screens show them.

That last point is the hard one. A Quadrava screen is a JSON document published to the registry and reviewed before a
protected environment (ADR-03): its form fields are fixed when it is written. A field that a project admin adds at run
time is not in any screen. The schema of Quadrava 0.1.0 has one data-driven part, `columnsFrom` of the `kanban`
(ADR-06); there is nothing like it for forms.

## Decision

1. **Data**, as the plan says. `odt_issue.field_definition (id, project_id, key, label, type, options, required_if,
   position)`, `key` unique per project (`^[a-z][a-z0-9_]{0,39}$`, a project has at most 50); `odt_issue.issue.custom
   jsonb NOT NULL DEFAULT '{}'` with a GIN index, for ODQL later (ADR-04).
2. **Types**: `text` (at most 1000 characters), `number` (decimal), `date` (ISO date), `select` (one of `options`),
   `multiselect` (some of `options`), `user` (a member of the project). Values are checked against the definitions
   when an issue is saved; a key without a definition is refused. `options` are codes with a label; removing an option
   keeps the values already saved, which are then shown as their code.
3. **`required_if` in CEL**, on the same variables as the guards of ADR-10 (`issue`, `user`, `can()`), for example
   `issue.type == 'bug'`; empty for an optional field, `true` for one always required. The engine of the guards moves
   from `workflow` to `shared` (one place that compiles and evaluates CEL), and the custom values reach the guards as
   `issue.custom.<key>`, so a transition can ask for a field (`has(issue.custom.customer)`).
4. **Who**: the admins of the project (`odt.project.edit`) define the fields, on `project-detail`; whoever edits an
   issue (`odt.issue.edit`) sets their values. Removing a definition removes its values from the issues of the project.
5. **Events and changelog**: a value that changes is a `FieldChange` named `custom.<key>`, with its values as text, so
   the history and the notifications see it like the other fields.
6. **Screens: a table of the fields with an edit dialog, until Quadrava has data-driven forms.** `issue-detail` gets a
   Fields tab: a table over `GET /api/issues/{key}/fields` (label, value as text, type) with an Edit row action. Its
   dialog has one field per type, each `visibleIf` on `row.type` (text, number, date); `select` and `user` use a
   `lookup` over `GET …/fields/{field}/options`, since `options` of a Quadrava field are fixed in the screen.
   `multiselect` uses the same lookup, one value at a time, until Q5. This is the temporary solution of AGENTS.md;
   **request Q6** to Quadrava: form fields from a data source (`fieldsFrom`, like `columnsFrom` of ADR-06), so that
   `issue-new` and `issue-detail` show the custom fields in the form itself. When Q6 is released the tab goes away.

## Alternatives considered

- **A column per field** (`ALTER TABLE` at run time). Real types and indexes, but a migration per project change, DDL
  from the application and a schema that differs per tenant: against ADR-09 and Flyway.
- **Rows per value** (EAV, `field_value (issue_id, field_id, value)`). Easy to index per field, but every read joins and
  ODQL turns into one join per condition; JSONB with GIN gives the same queries with one table.
- **Screens generated per project.** The back end writes and publishes `issue-detail-<project>` when the fields
  change. The form would be right at once, but the screens of `ui/` would no longer be the only source, and in a
  protected environment every change of a field would wait for a reviewer (ADR-03).
- **Waiting for Q6** before any custom field. Simpler, but it leaves E2 incomplete for a release of Quadrava.

## Consequences

- One Flyway migration in `issue` (definitions and the `custom` column); `Guards` moves to `shared`, with its tests.
- The API of the issues takes and returns `custom` (an object of values); the screens of 0.1.0 show it only in the
  Fields tab.
- Until Q6, a custom field is not in `issue-new`: it is set after the issue is created, and a required one makes the
  next save of the issue fail until it is set. The screen says it in the help text of the tab.
