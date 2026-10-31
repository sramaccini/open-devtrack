# ADR-03: Where the screens come from in production

Status: Accepted (31 October 2026; direction decided by the maintainer, details approved) —
decision D3 of the project plan

## Context

The screens, data sources, navigation, themes and translations of OpenDevTrack are a Quadrava project in `ui/`. In
production something must give them to the runtime. The plan proposed «static files in the image for 1.0, a registry
in the monolith in 1.x».

Checked in the sources of Quadrava 0.1.0 (`runtime/src/app/runtime-config.ts`, `docker/runtime/README.md`): the
runtime reads its definitions from **two sources only**, the built-in examples or a registry that implements
`contracts/registry.yaml` (0.6.0). There is no way to point it at a folder of static files.

The maintainer decided: **the registry is implemented inside OpenDevTrack and serves the definitions to the runtime**,
starting from the reference implementation of Quadrava.

## Decision

**A module `uiregistry` of the monolith implements `registry.yaml`; the runtime image reads from it
(`QUADRAVA_REGISTRY_APP=opendevtrack`), through the same origin.**

```
CI / deploy: quadrava publish ui/ ──▶ POST /registry/…  (draft → review → published, per environment)
browser ──▶ quadrava-runtime ──/registry/──▶ monolith: module uiregistry ──▶ PostgreSQL odt_uiregistry
```

Details:

- **Starting point**: `reference/registry` of Quadrava (`com.logicwarez.quadrava.reference.registry`, about 950 lines:
  `RegistryController`, `VersionStore`, `SchemaValidation`, `References`, `Model`). Its code is copied once into the
  module, with its MPL-2.0 headers and a note of the Quadrava commit it comes from, then belongs to OpenDevTrack. Same
  licence and same author: nothing to clear.
- **What changes from the reference**: versions stored in PostgreSQL (schema `odt_uiregistry`, one Flyway folder)
  instead of files or memory; security from the authz-library session and `odt.uidefinition.*` permissions instead of a
  JWT resource server and realm roles; the approver never the author, as in the reference; Java 25 and the module
  rules of Spring Modulith.
- **Proven by the kit**: `conformance:registry` of the Quadrava version pinned in ADR-01 runs in CI against the module.
- **Publication**: the `ui/` folder is published by `quadrava publish` (CLI of Quadrava 0.2.0) in the pipeline; only
  approved versions reach the protected environments (`testing`, `production`), as in the contract.

## Alternatives considered

- **Static files through a new source of the runtime** (a request to Quadrava). No registry to maintain, but no review
  flow and no path to the customisation of screens by administrators in 1.x.
- **The reference registry of Quadrava as a separate service.** Labelled «not for production» by Quadrava, and a
  second deployable with its own security.

## Consequences

- One more module in F1 (`uiregistry`, the 12th application module), with its tests and the registry kit.
- **Open point**: `quadrava publish` authenticates with a token from an environment variable, while the monolith knows
  only the authz-library session. To settle in F1: a bearer token of Keycloak accepted only on `/registry/**` writes (the
  reference does this), or a publication step inside the deployment.
- Code copied from the reference does not follow later fixes of Quadrava by itself: the Quadrava changelog is read at
  every raise of the pinned version, and fixes to the contract come with the kit.
