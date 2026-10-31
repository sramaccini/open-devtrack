# OpenDevTrack back end

The monolith: Spring Boot 4.1, Java 25 (ADR-02), with login, session and global roles from the authz-library libraries
`com.logicwarez.authz:authz-bff` and `authz-rbac` (which bring `authz-session`). In F0 it contains only that; the
Spring Modulith modules come in F1.

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

The application needs PostgreSQL, Redis and Keycloak; the quickest way to have them, with the Quadrava runtime in
front, is `e2e/authz/run.sh up` ([docs/check-authz-end-to-end.md](../docs/check-authz-end-to-end.md)).

Configuration (`application.yml`): `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `IAM_ISSUER_URI`,
`IAM_CLIENT_ID`, `IAM_CLIENT_SECRET`, `FRONTEND_URL` (the address of the runtime), `PORT`. The profile `dev` adds the
development tenants and users and the session cookie over http.
