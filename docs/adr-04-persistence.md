# ADR-04: Persistence for commands, search and reports

Status: Proposed (31 October 2026) — decision D4 of the project plan

## Context

The modules write aggregates (an issue with its fields, a board with its columns) and read large, dynamic sets: ODQL
queries turned into SQL, board columns with hundreds of cards, aggregates for burndown and cumulative flow. The options
were JPA only, or JPA for the commands plus jOOQ for search and reports.

authz-library, inside the same process, uses `JdbcClient` and its own Flyway history (`authz_schema_history`); it does not
constrain the choice of the application.

## Decision (proposed)

**Spring Data JPA (Hibernate 7) for the commands, jOOQ for search and reports.**

- JPA where an aggregate is loaded, changed and saved, with optimistic locking (`version`, HTTP 412).
- jOOQ where the SQL is built at run time (ODQL → parametric SQL, never concatenated strings) and for the read models
  of `reporting`.
- Flyway, one folder and one schema per module (`odt_issue`, `odt_agile`, …); jOOQ classes generated from the
  migrations in the build.

## Alternatives considered

- **JPA only.** Criteria API or JPQL for ODQL: dynamic queries become hard to read and to keep safe; aggregates over
  JSONB and `tsvector` fall back to native SQL anyway.
- **JdbcClient only, like authz-library.** Light, but the commands lose optimistic locking and dirty checking for free and
  ODQL still needs a typed builder.

## Consequences

- Two ways to reach the database, with a clear line: the module tests check that search and reporting never write.
- The code generation of jOOQ needs a database at build time (Testcontainers PostgreSQL), or the DDL parser of jOOQ
  on the migrations: to settle in F1.
