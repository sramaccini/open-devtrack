# ADR-01: How OpenDevTrack gets Quadrava

Status: Proposed (31 October 2026) — decision D1 of the project plan

## Context

The front end of OpenDevTrack is a Quadrava project: JSON documents only. Something has to run them, and the rule of
the project is that Quadrava is used, never cloned or forked. The plan listed three ways: npm packages `@quadrava/*`,
a container image of the runtime, git tags.

Since the plan was written Quadrava has chosen for itself (Quadrava ADR-20, 31 October 2026): one semantic version for
everything, a changelog, a release workflow on `v*` tags, and the runtime published as the image
`ghcr.io/sramaccini/quadrava-runtime` (tags `0.1.0`, `0.1`, `latest`), configured at start-up from `QUADRAVA_*`
variables or a mounted `runtime-config.json`. Version 0.1.0 is out. The command line `quadrava validate` /
`quadrava publish` (`@sramaccini/quadrava-cli` on GitHub Packages) comes with 0.2.0, not released yet.

## Decision (proposed)

**OpenDevTrack runs the runtime image of a fixed Quadrava version and never builds Angular.**

```
ui/ (JSON) ──quadrava validate (CI)──▶ ok
     │
     └─▶ definitions ──▶ quadrava-runtime:<x.y.z> (nginx, :8080) ──/bff, /api, /authz──▶ monolith
```

- The exact version is pinned in one place (the Compose and Helm files: `quadrava-runtime:0.1.0`, never `latest` or
  the minor line), raised by a pull request that reads the Quadrava changelog.
- The image is the front: `QUADRAVA_BFF_URL` points at the monolith, so the browser talks to one origin. Behind TLS the
  monolith honours `X-Forwarded-Proto` (`server.forward-headers-strategy=framework`, already the default of authz-library).
- CI validates `ui/` with the Quadrava CLI of the same version as the image, so the schemas checked are the ones the
  runtime reads.
- npm packages are not used: OpenDevTrack has no Angular code to import them into.

## Alternatives considered

- **npm packages `@quadrava/*`.** They would make OpenDevTrack an Angular application, which is what the plan wants
  to avoid; Quadrava does not publish its libraries anyway.
- **Git tags.** A fixed point, but the consumer must clone and build: refused by the plan.

## Consequences

- F1 needs Quadrava **0.2.0** released (the CLI for the CI of `ui/`). Until then, `ui/` is checked by running the
  validation of a Quadrava checkout at tag `v0.1.0` in CI, never by copying it here.
- How the definitions reach the image in production is ADR-03: a registry module of the monolith, which the image
  reads with `QUADRAVA_REGISTRY_APP`.
- GitHub Packages and GitHub Container Registry need a token with `read:packages` in CI and on developer machines,
  also for public packages (Maven and npm).
