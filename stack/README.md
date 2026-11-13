# OpenDevTrack in Docker

The whole application in containers with one command, to try it and review it: the back end built from `backend/`,
the Quadrava runtime with the screens of `ui/`, Keycloak, PostgreSQL, Redis and a mail catcher, with two sample
projects. Development only: plain HTTP, the profile `dev` of the back end (development tenants and users), Keycloak
with its embedded database. It is not a way to install OpenDevTrack.

```
browser ──▶ quadrava-runtime 0.15.0 (:18090) ──/bff /authz /api /registry──▶ backend (profile dev, image from backend/)
                                                                               │ in the network of Keycloak
            Keycloak 26.8.0 (:18180, realm opendevtrack) ◀── OIDC, on localhost ┤
            PostgreSQL 18 (volume pgdata) ◀─────────────────────────────────────┤
            Redis 8.2 ◀── sessions ─────────────────────────────────────────────┤
            Mailpit 1.27 (mails at :18025) ◀── SMTP ────────────────────────────┘
```

## What it needs

- Docker with Compose, bash and curl (on Windows, Git Bash).
- A GitHub token with `read:packages`, for authz-library (built into the back end) and the Quadrava CLI (which
  publishes `ui/`): `PACKAGES_TOKEN`, or the token `npm.pkg.github.com` already has in `~/.npmrc` (ui/README.md).
  It goes into the build as a BuildKit secret, never into an image.

No JDK, Maven or Node on the host: they run in containers.

## Commands

```bash
stack/stack.sh up       # first time: credentials, image, containers, ui/ published, sample projects
stack/stack.sh info     # addresses, users and their password
stack/stack.sh publish  # after a change of ui/
stack/stack.sh up       # after a change of backend/: the image is built again
stack/stack.sh logs     # the log of the back end
stack/stack.sh hooks    # what the webhook receiver got (address http://echo:8080/anything)
stack/stack.sh down     # stop, keeping the data
stack/stack.sh reset    # stop and delete the data; the next up starts empty and adds the samples again
```

Then open http://localhost:18090 and sign in as `mario.rossi` (one tenant, ACME, admin of its users) or `anna.bianchi` (two tenants, global admin in Globex: ADR-22),
with the password `info` prints (`DEV_USER_PASSWORD` in `stack/.work/.env`, generated at the first `up`). The mails
of the notifications are at http://localhost:18025. A webhook of a project (ADR-21) can post to the receiver `echo` of the stack,
`http://echo:8080/<any path>`, which prints each request with its headers: `stack.sh hooks`. The back end allows that
address with `ODT_WEBHOOKS_ALLOW_PRIVATE`. The secrets of the webhooks are encrypted with `ODT_SECRET_KEY` (ADR-23),
generated in `stack/.work/.env` too (also in an `.env` written before it existed).

Ports: `FRONT_PORT` (18090), `KEYCLOAK_PORT` (18180), `APP_PORT` (18080, the back end for tools on the host),
`MAILPIT_PORT` (18025). They differ from the end-to-end check (`e2e/authz`, 28xxx), so both can run.

## The sample projects

Added by `up` when the project `WEB` is not there (`seed` does it alone), as `mario.rossi`, with dates relative to the
day of the seed:

- **WEB, Website relaunch** (anna.bianchi developer): an epic, stories, bugs and tasks with priorities, labels,
  assignees, reviewers and due dates, some overdue; issues in progress and done, a link, comments with a mention;
  Sprint 1 started four days ago with five issues and their points, Sprint 2 planned.
- **MOB, Mobile app** (anna.bianchi admin): three issues.

So the dashboard, the lists, the board, the backlog, the burndown, the search and the notifications of `anna.bianchi`
have something to show from the first login.

## How it is put together

- `backend/Dockerfile`: Maven with Temurin 25 builds the jar (tests skipped: CI runs them), Temurin 25 JRE runs it as
  a user without privileges. The Maven repository is a BuildKit cache, so later builds download nothing.
- The back end shares the network of the Keycloak container (`network_mode: service:keycloak`): there
  `http://localhost:18180` is Keycloak, as in the browser, so the issuer of the tokens and the address the back end
  reads it from are the same without a proxy or a hosts entry. The other containers reach the back end by the alias
  `backend` of that network.
- The realm and the runtime configuration are those of the end-to-end check (`e2e/authz/realm-opendevtrack.json`,
  `e2e/authz/runtime-config.json`); the brand is mounted from `brand/` as there.
- `ui/` is published to the environment `development` of the registry by the service `publish` (Node 24 and the
  Quadrava CLI 0.15.0), with an access token of `mario.rossi` from the tools client.
- Keycloak imports the realm only when its container is created: after a change of the realm, `down` then `up`.
- The API with a token (docs/api.md): the client `opendevtrack-ci` is a service account (client credentials, its
  secret `CI_CLIENT_SECRET` in `.work/.env`), whose user `service-account-opendevtrack-ci` is a member of `ACME`;
  add it to a project to let it work there. `http://localhost:18080/v3/api-docs` is the OpenAPI definition.
