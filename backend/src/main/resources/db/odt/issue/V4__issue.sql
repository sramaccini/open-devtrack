-- Issues (module issue): numbered per project (ODT-1, ODT-2…, the number from the project), with the tenant repeated
-- for the isolation (ADR-09). The project is only an identifier: no foreign key to another module's schema.

CREATE TABLE odt_issue.issue (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   varchar(50)  NOT NULL,
    project_id  uuid         NOT NULL,
    number      bigint       NOT NULL,
    type        varchar(20)  NOT NULL CHECK (type IN ('TASK', 'BUG', 'STORY', 'EPIC')),
    summary     varchar(255) NOT NULL,
    description text,
    status      varchar(50)  NOT NULL,
    priority    varchar(20)  NOT NULL CHECK (priority IN ('LOWEST', 'LOW', 'MEDIUM', 'HIGH', 'HIGHEST')),
    assignee    varchar(150),
    reporter    varchar(150) NOT NULL,
    labels      text[]       NOT NULL DEFAULT '{}',
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0,
    UNIQUE (project_id, number)
);

CREATE INDEX ix_issue_project_status ON odt_issue.issue (project_id, status);
CREATE INDEX ix_issue_assignee ON odt_issue.issue (assignee);
