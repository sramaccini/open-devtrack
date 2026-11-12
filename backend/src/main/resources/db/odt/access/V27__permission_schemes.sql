-- Permission schemes (ADR-22): which project permissions each of the three project roles gives. A tenant owns its
-- schemes; its default one serves every project that has no other, and is made from the sets of ProjectRole the
-- first time the tenant needs it.
CREATE TABLE odt_access.permission_scheme (
    id          uuid         PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    name        varchar(100) NOT NULL,
    description varchar(500),
    is_default  boolean      NOT NULL DEFAULT false,
    viewer      text[]       NOT NULL,
    developer   text[]       NOT NULL,
    admin       text[]       NOT NULL,
    updated_by  varchar(150) NOT NULL,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_permission_scheme_name ON odt_access.permission_scheme (tenant_id, lower(name));
CREATE UNIQUE INDEX ux_permission_scheme_default ON odt_access.permission_scheme (tenant_id) WHERE is_default;

-- The scheme a project uses, when it is not the default of its tenant
CREATE TABLE odt_access.project_scheme (
    project_id uuid PRIMARY KEY,
    scheme_id  uuid NOT NULL REFERENCES odt_access.permission_scheme (id)
);

CREATE INDEX ix_project_scheme_scheme ON odt_access.project_scheme (scheme_id);
