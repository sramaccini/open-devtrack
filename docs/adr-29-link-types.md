# ADR-29: Link types configured by the admin of the tenant

Status: Accepted (28 November 2026; decided by the maintainer, each proposal of A to C accepted) — F5

## Context

Jira lets the administrator of a site define the types of link between work items: each has a name and two
descriptions, one for each end («blocks» / «is blocked by»), and the defaults are Blocks, Cloners, Duplicate and
Relates. The page of an item lists its links grouped by those descriptions, and JQL finds them with `issueLinkType`
and `issue in linkedIssues(KEY, "blocks")`.

OpenDevTrack has four types fixed in code (`LinkType`: `blocks`, `relates`, `duplicates`, `clones`), stored by name in
`odt_issue.issue_link.link_type` with a check of the four values. The relations of the API (`blocks`, `blocked_by`,
`relates`, …) come from the enum; the UI lists them in the select of the dialog and translates them with
`values.relation.*` in the catalogues; the export translates them with `relation.*`. `clones` is written by «Clone».
The links are not in the search document, so ODQL cannot find them. This is T6 of the plan towards Jira: the section of
the page is done (741be0e), the configurable types are left to this decision.

## Decision

1. **Link types are data of the tenant.** A new table `odt_issue.link_type` (id, `code`, `outward`, `inward`,
   `system`, `created_at`): `code` is the stable name used by the API and by ODQL (`[a-z][a-z0-9_]{1,29}`, unique in
   the tenant), `outward` and `inward` are the two descriptions, plain text in the language the admin writes them, as
   the names of statuses and custom fields are. The four types of today are its first rows, with `system` true.
2. **The admin of the tenant manages them**, as groups and permission schemes (`odt.project.admin`, `ProjectAccess.TENANT_ADMIN`): `GET
   /api/link-types` for every user who sees a project (the UI needs them to add a link),
   `POST /api/link-types`, `PATCH /api/link-types/{code}` (the descriptions only; the code never changes) and
   `DELETE /api/link-types/{code}`. A new screen «Link types» in the administration lists them.
3. **System types.** `clones` stays, since «Clone» writes it, and cannot be deleted; its descriptions can be
   changed. The others of today are ordinary rows, deleted like any other (question A).
4. **The relation of the API keeps its shape.** A relation is `code` read from the `from` end or
   `code:inward` read from the other one, so the relations of today (`blocks`, `blocked_by`, …) still work for the
   four system types as aliases, and the API stays compatible within 1.x. A link answers `relation` (as today),
   `type` (the code), `outward` (true at the `from` end) and `label` (the description to show).
5. **The UI shows the labels of the back end.** The select of «Add linked work item» reads `GET /api/link-types`
   (each type twice, one per end, or once when both descriptions are equal, as `relates`), and the section groups by
   `label`. The catalogue keys `values.relation.*` stay for the four system types only while their descriptions are
   unchanged (question B).
6. **Search.** The search document gets the links as `type:KEY` pairs, and ODQL gets `linked = WEB-4` (linked in any
   way), `linkType = blocks` (any link of that type), and the function `linkedIssues(WEB-4)` and
   `linkedIssues(WEB-4, blocks)` as in Jira, for `key IN linkedIssues(...)` (question C).
7. **History and events** do not change: a link added or removed is already a change of the field `links` of both
   issues, written as the relation the issue reads and the other key («blocks WEB-2»); with custom types the relation
   is the one of point 4.

## Questions for the maintainer (each proposal accepted)

- **A. Delete of a type in use.** Jira asks to move its links to another type or delete them. Proposal: the same, in
  one call with `replaceWith` (a code) or `deleteLinks: true`, in a transaction, each issue with its event; without
  either, 409 with the count of links.
- **B. Language of the descriptions.** Statuses and custom fields have one name, not translated. Proposal: the same for
  link types (one `outward` and one `inward`); the four system types are created with the English descriptions and
  the Italian UI keeps translating them only while they are unchanged. Alternative: descriptions per language
  (`{"en": …, "it": …}`), more faithful to the two catalogues but a new pattern in the project.
- **C. ODQL now or later.** Proposal: points 1–5 first (types, API, UI), ODQL of point 6 in a second step of the same
  task, since it changes the search index and needs a rebuild of the documents.

## Consequences

- A migration creates `odt_issue.link_type` per tenant with the four system types and turns
  `issue_link.link_type` into a reference to it (the check of the four values goes); `LinkType` becomes a lookup.
- The export, the webhooks payload (`relation` as today) and the JSON of an issue use the stored descriptions.
- The guides of issues, search and administration, the API and the screen of the administration change; the tests of
  links run with a custom type too.

## Options considered

- **Keep the enum, add more fixed types.** No administration needed, but every installation gets the same list and a
  new type needs a release. Rejected: it is what T6 asks to change.
- **Link types per project.** More flexible, but Jira has them per site, and a link joins issues of two projects,
  which would then disagree on the meaning. Rejected.
