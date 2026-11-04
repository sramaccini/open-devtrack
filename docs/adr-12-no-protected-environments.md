# ADR-12: No protected environments in the registry

Status: Accepted (4 November 2026; decided by the maintainer) — replaces the protected environments of ADR-03

## Context

ADR-03 made `testing` and `production` protected environments of the registry: only a version approved by someone
other than its author is published there. With one maintainer, who reviews every change before it reaches `main`, a
second approval in the registry only repeats that review and needs two accounts to publish. The Quadrava CLI cannot
approve (request Q7), so publishing there needed `tools/publish-ui.mjs` with an author and a reviewer token.

Quadrava 0.5.0 answered Q7 in the contract instead of the CLI: in `contracts/registry.yaml` 0.7 (compatible with 0.6)
protected environments are a choice of each registry's configuration, not an obligation of the contract.

## Decision

1. **No protected environment by default**: `odt.uiregistry.protected-environments` is empty, so every version can be
   published in every environment. The review stays in the API (request, approve, reject, never by the author), and an
   installation that wants it again lists its environments in the property.
2. **The review is the one of the code**: what reaches `ui/` on `main` has been reviewed there; `quadrava publish` of
   the pinned CLI publishes it with one token. `tools/publish-ui.mjs` goes.
3. The end-to-end environment keeps `production` protected while it runs the registry kit of Quadrava 0.2.1, which
   expects one; with the kit of 0.5.0 it takes `--protected-env none` instead.

## Alternatives considered

- **Keep the protected environments and `tools/publish-ui.mjs`** as the way to publish there. A second review of what
  was already reviewed, and a script of our own for something the contract no longer requires.
- **A reviewer step in the CLI** (request Q7 as asked). Declined by Quadrava, which moved the choice to the registry.

## Consequences

- `RegistryApiTest` sets the protected environments it tests: the behaviour stays, only the default changes.
- Q7 is closed by the contract 0.7 of Quadrava 0.5.0; OpenDevTrack still runs Quadrava 0.2.1.
