# OpenDevTrack: project rules

OpenDevTrack is an open-source issue tracker: its front end is a Quadrava project, its back end a Spring Boot + Spring Modulith monolith on PostgreSQL, with login, session and global roles from the authz-library libraries. What the project does: [README.md](README.md). Status and next steps: [HANDOFF.md](HANDOFF.md). Decisions: [docs/](docs/README.md).

This file collects the rules to follow at all times.

## 1. Way of working

- **Do what was asked.** An unrequested change (a refactoring, a new dependency, a change in behaviour) is proposed first and waits for approval. Problems found along the way are reported separately, not fixed quietly.
- **Architectural review.** Inconsistencies, missing pieces and shortcomings against the requirements are pointed out explicitly, even when they are not the subject of the work.
- **Look at the code first.** Behaviour, defaults and names are checked in the sources (here, in Quadrava, in authz-library) before writing about them.
- **Light, governable solutions.** Up-to-date stack, simple DSLs and expressions (CEL); no heavy rule engines or extra frameworks for small problems.
- **One task, one commit, one push.**
- **Report outcomes faithfully.** If a test fails or a check was skipped, say so.
- **Decisions are ADRs** in `docs/`. A proposed ADR is not applied until it is accepted; a phase does not start before the decisions it depends on are accepted.

## 2. Quadrava and authz-library are used, not cloned

- Quadrava is a versioned dependency (its runtime image, its CLI, its schemas and contracts). No Angular code, no copy of the runtime, no fork in this repository.
- authz-library is a versioned dependency (`com.logicwarez.authz`). OpenDevTrack writes no security code of its own beyond its project roles.
- Something missing in either is requested and built in its own repository, with its tests and release; OpenDevTrack adopts it by raising the version. Until then, a temporary solution here, written down where it is used.
- The contracts belong to Quadrava (`contracts/*.yaml`): OpenDevTrack implements them and passes their conformance kits.

## 3. Languages

The project is published as open source: **everything is in English** (code, comments, tests, documentation, commit messages, log and Problem titles). User-facing texts come in English and Italian catalogues.

## 4. Commits and history

- Conventional Commits in English, with a scope when it helps: `feat(issue): …`, `fix(agile): …`, `docs(adr): …`. The body explains what changes and why.
- Commits are in the repository author's name only: **no trailers** (`Co-Authored-By`, automatic `Signed-off-by`, session identifiers), and no references to tools used to write code or text in commits, comments, documents or pull requests.
- Tests and documentation updated in the same commit as the change.
- Never `push --force`, history rewrites or `reset --hard` on `main` without explicit confirmation.
- Never commit secrets, tokens, `.env` files or personal data. Local environments generate their test credentials in gitignored files.
- Licence: MPL-2.0 ([LICENSE](LICENSE)). External contributions need the [CLA](CLA.md) accepted in the pull request ([CONTRIBUTING.md](CONTRIBUTING.md)); the «no trailers» rule is about our own commits.

## 5. Formatting

`.editorconfig`: UTF-8, LF, two spaces (four in Java), final newline. New code looks like the code around it: same names, same density of comments, same idioms.

## 6. Brand

The logo, icons, colours and type are in [brand/](brand/README.md) and are used as they are wherever the project shows its identity: the `ui/` theme (palette and favicon), documentation, slides, the site. No redrawn, recoloured or locally edited copies; a missing variant is added to `brand/` first, following its rules.
