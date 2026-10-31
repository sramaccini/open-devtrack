-- Projects of the tenants (module project, E1). The key is unique per tenant (ADR-09); issue_seq numbers the issues.

CREATE TABLE odt_project.project (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   varchar(50)  NOT NULL,
    project_key varchar(10)  NOT NULL CHECK (project_key ~ '^[A-Z][A-Z0-9]{1,9}$'),
    name        varchar(100) NOT NULL,
    description varchar(2000),
    lead        varchar(150) NOT NULL,
    issue_seq   bigint       NOT NULL DEFAULT 0,
    created_by  varchar(150) NOT NULL,
    created_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_project_key UNIQUE (tenant_id, project_key)
);
