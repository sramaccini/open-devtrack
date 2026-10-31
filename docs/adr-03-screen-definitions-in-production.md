# ADR-03: Where the screens come from in production

Status: Proposed (31 October 2026) — decision D3 of the project plan

## Context

The screens, data sources, navigation, themes and translations of OpenDevTrack are a Quadrava project in `ui/`. In
production something must give them to the runtime. The plan proposed «static files in the image for 1.0, a registry
in the monolith in 1.x, when administrators can customise the screens».

Checked in the sources of Quadrava 0.1.0 (`runtime/src/app/runtime-config.ts`, `docker/runtime/README.md`): the
runtime reads its definitions from **two sources only**, the built-in examples (`examples`) or a registry that
implements `contracts/registry.yaml` (`registry`). There is no way to point it at a folder of static files. The
recommendation of the plan cannot be applied as it stands.

## Decision (proposed)

**Static definitions for 1.0, through a new optional source of the Quadrava runtime; a registry only in 1.x.**

- Request to Quadrava (**Q1**, in its repository, with its tests and release): a third definitions source, for
  example `{ "source": "project", "url": "/ui" }`, that reads a project folder (`project.json` and its resources) as
  Studio writes it, and an image variable for it. Optional by construction: without it nothing changes for other
  users of Quadrava.
- OpenDevTrack builds its image from the runtime image of the fixed version plus the `ui/` folder; nothing to deploy
  besides the image and the monolith.
- During F1, before Q1 is released, development uses the reference registry of Quadrava in Docker (development only)
  and `quadrava publish`.

## Alternatives considered

- **The monolith implements the read side of `registry.yaml`** from files in its classpath. No Quadrava change, but
  OpenDevTrack would carry a partial implementation of a contract and its conformance kit; it is the 1.x path anyway,
  and doing half of it now adds work twice.
- **The reference registry of Quadrava in production.** It is labelled «not for production» by Quadrava.

## Consequences

- One more Quadrava item on the path of F1, small but with a deadline: needed before the first deployment to the test
  environment (end of F1).
- Screens in production change only with a new image: reviewed in the pull request, not in a registry. Customisation by
  administrators stays a 1.x topic.
