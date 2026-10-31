# HANDOFF — OpenDevTrack

Notes for whoever continues the work. Read [README.md](README.md), [AGENTS.md](AGENTS.md) and [docs/](docs/README.md)
first. The project plan (Italian) is the source of the requirements; parts of it are older than the work done since in
Quadrava and authz-library, and the ADRs say where.

## Status

**31 October 2026 — phase F0.**

- Repository github.com/sramaccini/open-devtrack, branch `main`: licence MPL-2.0 with CLA, contribution rules and
  project rules as Quadrava (ADR-05).
- Decisions D1–D9 written as ADRs in `docs/`. Accepted: D5 (licence) and the direction of D3 (registry inside the
  monolith, from the Quadrava reference). The others are **proposed**: F1 does not start before they are accepted.
- `backend/`: the monolith with only the authz-library 2.0.1 libraries, the security chain, the `odt.*` permission
  catalogue (repeatable Flyway migration, ADR-07) and the development data.
- End-to-end check passed (`docs/check-authz-end-to-end.md`): Quadrava kits bff 13/13 with and without a session, authz
  11/11, tenant switch kept across logins, login in the browser through the runtime image 0.1.0.
- `brand/`: the Commit Ring logo with its icons, favicon, colours and rules of use; the README shows it.

## Requests to the dependencies

In `docs/README.md`: Q2 (Quadrava 0.2.0 with its CLI, start of F1), A1 (extension point for project permissions, F2),
A2 (schema of the RBAC tables, F1), A3 (Problem texts in English). Each is built in its own repository.

## Environment of this machine

- JDK 25 at `C:\Program Files\Eclipse Adoptium\jdk-25.0.5.8-hotspot`, not in the `PATH` (`java` is 21):
  `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.5.8-hotspot"`.
- `TEMP` in short 8.3 form: `export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\tmp\ud'` before `mvn`, and
  `E2E_JAVA_OPTS='-Djdk.net.unixdomain.tmpdir=C:\tmp\ud'` for `e2e/authz/run.sh up`. A problem of the machine: not in
  the poms.
- Node through fnm: `eval "$(fnm env --shell bash)"; fnm use 24.21.0`. No `gh`, no Python.
- No `~/.m2/settings.xml`: the authz-library 2.0.1 artifacts resolve from the local Maven repository, where the
  authz-library build installed them. A clean machine or CI needs the token (backend/README.md).
- Ports 8180 and 8090 are used by containers of other projects: the e2e environment uses 25432, 26379, 28080, 28090,
  28180.
- Commits only as `Simone Ramaccini <s.ramaccini@gmail.com>` (repository `git config`; the global one has another
  address), no trailers or tool attributions anywhere.

## Next steps

1. Approval of the proposed ADRs (D1, D2, D4, D6–D9, details of D3).
2. Then F1: Spring Modulith skeleton with the modules and `verify()`, the `uiregistry` module from the Quadrava
   reference registry (ADR-03), the `ui/` project with navigation and theme (palette and favicon from `brand/`), CI with the kits (bff, authz, registry).
