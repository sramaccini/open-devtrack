# Installation

## The parts

```
browser ──https──▶ proxy (TLS) ──/bff /logout /oauth2 /login/oauth2 /api /registry /authz /telemetry /v3/api-docs──▶ back end :8080
                        │                                                                                         │  metrics :9090
                        └── everything else ──▶ Quadrava runtime :8080 (the screens of ui/,                       ├──▶ PostgreSQL
                                                published in the registry of the back end)                         ├──▶ Redis
browser ──https──▶ Keycloak (login) ◀── OIDC, back-channel logout ────────────────────────────────────────────────┘
```

- **Back end**: the image `ghcr.io/sramaccini/opendevtrack-backend` of a release, signed, with its SBOM
  ([operations](../operations/README.md), «Verifying the image»). It holds the API, the BFF of the browser (login,
  session, CSRF), the registry of the screen definitions, and creates its schemas at each start (Flyway).
- **Quadrava runtime**: `ghcr.io/sramaccini/quadrava-runtime` at the version the release pins (0.32.0). It serves the
  pages and loads the screens of `ui/` from the registry of the back end.
- **Proxy**: ends TLS and gives the browser one origin. The paths of the BFF go to the back end and everything else to
  the runtime, so the back end sees `X-Forwarded-Proto: https` and builds its addresses on https.
- **Keycloak** 26: the login, the passwords and OTP. The users, tenants and roles of OpenDevTrack are in its own
  database (authz-library), keyed by the Keycloak username.
- **PostgreSQL** 18 and **Redis** 8.2: the data, and the sessions with the rate limits.
- Optional: an SMTP server for mail, clamd for the antivirus, Vault or OpenBao for the key of the secrets, S3 or a
  volume for the attachments ([configuration.md](configuration.md)).

## With Docker Compose

[`deploy/compose/`](../../deploy/compose) runs all of it on one host. Two parts are optional and are chosen with
`COMPOSE_PROFILES`:

- `proxy` is Caddy, with certificates from Let's Encrypt;
- `keycloak` is a Keycloak that keeps its data in the same PostgreSQL.

An installation that already has its own proxy or identity provider leaves that profile out and does what the
[Caddyfile](../../deploy/compose/Caddyfile) does, or what [keycloak.md](keycloak.md) says.

What it needs:

- A host with Docker and Compose, two DNS names (for example `tracker.example.org` and `login.example.org`), and
  ports 80 and 443 reachable from the internet for the certificates.
- The files of the release: the archive `opendevtrack-<tag>.tar.gz` of its GitHub release
  ([release.md](../release.md)), checked against its `.sha256`. It holds `deploy/`, `ui/`, `keycloak/` and `brand/`
  with the layout of the repository, and its `.env.example` already names the image of the release by digest. A
  checkout of the tag works too.

  ```bash
  sha256sum -c opendevtrack-v1.0.0.tar.gz.sha256 && tar -xzf opendevtrack-v1.0.0.tar.gz && cd opendevtrack-v1.0.0
  ```
- A GitHub token with `read:packages`. The Quadrava CLI that publishes `ui/` is on GitHub Packages, which asks for a
  token also to read ([#29](https://github.com/sramaccini/open-devtrack/issues/29)).

### 1. Settings

```bash
cd deploy/compose
cp .env.example .env && chmod 600 .env
```

Set every value of `.env`. Each secret must be a long random string, for example from `openssl rand -hex 32`, and
`ODT_BACKEND_IMAGE` is the image of the release by digest. The optional settings of the back end go into
`backend.env`, from [`backend.env.example`](../../deploy/compose/backend.env.example). Keep only the lines that are set:
an empty value counts as set for some of them, so an empty `SPRING_MAIL_HOST` would turn mail on with no host.

The password policy of the realm refuses the passwords of a list. Put the list in
`password-blacklists/breached-passwords.txt`, one password a line, in lower case ([keycloak.md](keycloak.md)). Keycloak
does not start without it.

### 2. Start

```bash
./odt.sh up
```

On the first start:

- PostgreSQL creates the databases `opendevtrack` and `keycloak`, each owned by a role that is not a superuser
  ([postgres/init.sh](../../deploy/compose/postgres/init.sh));
- Keycloak imports the realm of [deploy/keycloak](../../deploy/keycloak/realm-opendevtrack.json);
- the back end creates its schemas.

`up` returns once every container is healthy. The first start of the back end takes a minute or two.

### 3. The first administrator

The first administrator is a user of Keycloak, so add them there first:

1. Open the admin console of Keycloak at `https://login.example.org/admin`, with `KEYCLOAK_ADMIN` and
   `KEYCLOAK_ADMIN_PASSWORD`.
2. In the realm `opendevtrack`, add the user with a username and an email.
3. Give them the required actions *Update Password* and *Configure OTP* ([keycloak.md](keycloak.md)).

Then make that user the administrator of OpenDevTrack, once:

```bash
./odt.sh first-admin MAIN ada.lovelace ada.lovelace@example.org
```

This creates the tenant `MAIN` and gives the user these roles on it:

- `GLOBAL_ADMIN`: the administration of authz-library (users, roles, tenants);
- `ODT_ADMIN`: admin of every project of the tenant;
- `ODT_MEMBER`.

It also gives the role `ODT_UI_AUTHOR` to the service account of the client `opendevtrack-publisher`. authz-library
has no other way to create the first global administrator than this SQL, which it documents. After it, everything is
done in the Administration area ([administration.md](administration.md)).

### 4. The screens

```bash
./odt.sh publish-ui
```

This publishes the screens of `ui/` to the environment `production` of the registry, with the Quadrava CLI of the
pinned version and the token of the service account. Run it again after each upgrade. The runtime shows an empty page
until the screens are published.

Then the administrator signs in at `https://tracker.example.org`, and the users come next: an account in Keycloak and
a user with roles in the Administration area, with the same username.

### Checking the installation

`e2e/deploy/run.sh` does all of the above with an image built from the sources, in CI and on a workstation. It then
signs in over https and checks:

- HSTS on the pages and on the answers of the back end;
- the `__Host-` session cookie;
- the login theme;
- the permissions of the first administrator;
- the creation of a project;
- that the metrics cannot be reached through the proxy.

## Without Compose

The same parts in another platform, a Kubernetes cluster for example (a Helm chart is not part of 1.0):

- The back end with the variables of [configuration.md](configuration.md), and the secrets from the secret store of
  the platform. Its port 8080 is reachable only from the proxy, and the management port only from Prometheus.
- The runtime with `QUADRAVA_REGISTRY_APP=opendevtrack`, `QUADRAVA_REGISTRY_ENV=production`,
  `QUADRAVA_NAVIGATION=main`, `QUADRAVA_THEMES=odt-light,odt-dark,polar`, `QUADRAVA_LANGUAGES=en,it`,
  `QUADRAVA_TENANT_SWITCH_URL=/bff/tenant` and `QUADRAVA_HSTS`. The logo and the icons come with the screens, in
  the asset pack `odt` of the registry: nothing to mount.
- A proxy or ingress with the routing of the Caddyfile. Event streams must not be buffered, and the proxy must set
  `X-Forwarded-Proto` and `X-Forwarded-Host`.
- The realm, the first administrator and the screens as above: the SQL of `odt.sh first-admin` and the script
  `publish-ui.mjs` work against any PostgreSQL and registry.

The runtime can also forward the BFF itself, with `QUADRAVA_BFF_URL`. It then sets `X-Forwarded-Proto` to the scheme
of its own connection, which is `http` behind a TLS terminator, unless `QUADRAVA_TRUST_FORWARDED=true` (Quadrava
0.19.0) passes on the `X-Forwarded-Proto` and `X-Forwarded-Host` of the proxy in front. `deploy/compose` routes the
BFF from the proxy, as above, and does not need it.
