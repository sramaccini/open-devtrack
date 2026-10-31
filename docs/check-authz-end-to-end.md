# End-to-end check of authz-library (F0)

Result on 31 October 2026: **all checks passed**. The OpenDevTrack back end, built on the authz-library 2.0.1
libraries, talks to the Quadrava runtime 0.1.0 as the contracts of Quadrava ask, with the versions OpenDevTrack targets.

## What runs

```
browser / kits ──▶ quadrava-runtime 0.1.0 (:28090) ──/bff /authz /logout /oauth2──▶ backend/ (:28080, profile dev)
                                                                                    │ authz-bff, authz-session, authz-rbac 2.0.1
                         Keycloak 26.8.0 (:28180, realm opendevtrack) ◀── OIDC ─────┤
                         PostgreSQL 18.6 (:25432) ◀── RBAC schema + odt.* catalogue ┤
                         Redis 8.2 (:26379) ◀── Spring Session (indexed) ───────────┘
```

- `backend/`: the OpenDevTrack monolith as it is in F0, only the libraries and one `SecurityFilterChain`
  (`platform/SecurityConfig.java`). No Modulith modules yet: they are F1.
- `R__odt_permission_catalogue.sql`: the global permissions and roles of OpenDevTrack, applied by the Flyway of
  `authz-rbac` after its own migrations (ADR-07). With the `dev` profile, `V100__dev_tenants_and_users.sql` adds two
  tenants (ACME, GLOBEX) and two users: `mario.rossi` (one tenant) and `anna.bianchi` (two tenants).
- From F1 the registry kit calls `backend/` directly (`:28080/registry/**`) with access tokens of the public client `opendevtrack-tools` (password grant, development only).
- `e2e/authz/`: Compose file, Keycloak realm `opendevtrack` and the script. Credentials are generated at each first
  start into `e2e/authz/.work/.env` (gitignored).

## How to run it

```bash
export JAVA_HOME=<JDK 25>
e2e/authz/run.sh up
e2e/authz/run.sh check
e2e/authz/run.sh down
```

Maven must reach the authz-library packages: see [backend/README.md](../backend/README.md).

## Results

| Check | Result |
| --- | --- |
| Quadrava kit `conformance-bff`, no session | 6 checks run, 7 skipped (they need a session), 0 failed |
| Quadrava kit `conformance-authz`, sessions of `mario.rossi` and `anna.bianchi` | 11 of 11 |
| `odt.*` permissions in the session of `mario.rossi` | `odt.project.browse`, `odt.project.create`, `odt.user.browse` |
| Quadrava kit `conformance-bff`, session of `anna.bianchi` with two tenants | 13 of 13 (tenants, switch by POST only, CSRF, logout) |
| Quadrava kit `conformance-registry`, bearer tokens of `mario.rossi` (author) and `anna.bianchi` (reviewer), from F1 | 23 of 23 (validation, If-Match, four eyes, protected environment, references) |
| Tenant switch kept for the next login (ADR-08) | ACME → GLOBEX, then GLOBEX after a new login |
| In the browser: login through the runtime, the user menu | «Spazio di lavoro» with both tenants, GLOBEX selected, 2 permissions |

The kits come from Quadrava `main` at `9fa1916` (downloaded file by file, not cloned): the authorization kit with
session cookies is not in the tag `v0.1.0`. Pin the release that carries it once there is one.

## Found along the way

- **The login rate limit of authz-library works**: 20 starts of the login per minute per IP; a browser login right
  after the kits got `429` with a Problem. Expected, but the `check` makes about ten logins: running it twice in a
  minute fails.
- **Problem texts in Italian** in authz-library («Troppi tentativi di accesso»): request A3.
- **RBAC tables in the `public` schema**: authz-rbac has no schema setting; request A2, so that its tables live in
  their own schema like the modules.
- **Maven packages need a token**: GitHub Packages answers `401` without one, also to read. This machine has no
  `settings.xml`: the build used the copies of 2.0.1 already in the local Maven repository (installed by the
  authz-library build), not a download. CI needs the token as a secret.
- **The demo screens of the runtime show nothing to these users** («Nessuna funzione disponibile»): correct, the
  navigation is filtered by permissions they do not have.
