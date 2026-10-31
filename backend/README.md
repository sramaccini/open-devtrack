# OpenDevTrack back end

The monolith: Spring Boot 4.1, Java 25 (ADR-02), with login, session and global roles from the authz-library libraries
`com.logicwarez.authz:authz-bff` and `authz-rbac` (which bring `authz-session`), split into Spring Modulith modules
(`ModularityTests` checks their boundaries). The first with code is `uiregistry`, the registry of the screen definitions
(ADR-03).

## authz-library from GitHub Packages

The libraries are published on GitHub Packages (`https://maven.pkg.github.com/sramaccini/authz-library`), which asks for
a token also to read. Create a personal access token with the `read:packages` scope and add it to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>authz-library</id>
      <username>YOUR_GITHUB_USER</username>
      <password>YOUR_TOKEN</password>
    </server>
  </servers>
</settings>
```

The `id` must match the repository declared in `pom.xml`. In CI the same server is written from a secret.

## Build and run

```bash
export JAVA_HOME=<JDK 25>
mvn -f backend/pom.xml package
```

The tests that start the application (`RegistryApiTest`) run PostgreSQL 18 and Redis 8.2 with Testcontainers: they
need Docker.

The application needs PostgreSQL, Redis and Keycloak; the quickest way to have them, with the Quadrava runtime in
front, is `e2e/authz/run.sh up` ([docs/check-authz-end-to-end.md](../docs/check-authz-end-to-end.md)).

Configuration (`application.yml`): `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `IAM_ISSUER_URI`,
`IAM_CLIENT_ID`, `IAM_CLIENT_SECRET`, `FRONTEND_URL` (the address of the runtime), `PORT`. The profile `dev` adds the
development tenants and users and the session cookie over http.

## Registry of the screen definitions (`uiregistry`)

Implements `contracts/registry.yaml` 0.6.0 of Quadrava, adapted from its reference registry. Versions and publications
are in the schema `odt_uiregistry` (Flyway, `db/odt/uiregistry`). Documents are validated against the schemas of the
pinned Quadrava version in `src/main/resources/quadrava/schemas`, refreshed with `tools/sync-quadrava-schemas.sh
<version>`. The browser reaches it with the session; tools (`quadrava publish`, the registry kit) with a Keycloak
access token of a client listed in `odt.security.bearer.clients` (`platform/BearerTokenSecurity.java`).

- `odt.uidefinition.publish` (role `ODT_UI_AUTHOR`): save versions, request a review, publish.
- `odt.uidefinition.approve` (role `ODT_UI_APPROVER`): approve or reject, never a version of one's own.
- `odt.uiregistry.protected-environments` (default `testing,production`): only approved versions are published there.
- Any signed-in user reads what is published.
