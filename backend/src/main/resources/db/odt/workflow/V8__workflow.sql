-- Workflows (module workflow, ADR-10): one per project for now, copied from the built-in default when the project is
-- created. The project is only an identifier: no foreign key to another module's schema.

CREATE TABLE odt_workflow.workflow (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    tenant_id  varchar(50)  NOT NULL,
    project_id uuid         NOT NULL UNIQUE,
    name       varchar(100) NOT NULL
);

-- The category groups the statuses for boards and reports, whatever their names.
CREATE TABLE odt_workflow.status (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    workflow_id uuid        NOT NULL REFERENCES odt_workflow.workflow (id) ON DELETE CASCADE,
    code        varchar(50) NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]{0,49}$'),
    category    varchar(20) NOT NULL CHECK (category IN ('todo', 'in_progress', 'done')),
    position    int         NOT NULL,
    UNIQUE (workflow_id, code)
);

-- No from_status: the transition is allowed from every status. guard_cel: a CEL expression, empty for none.
CREATE TABLE odt_workflow.transition (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    workflow_id uuid        NOT NULL REFERENCES odt_workflow.workflow (id) ON DELETE CASCADE,
    code        varchar(50) NOT NULL CHECK (code ~ '^[a-z][a-z0-9_]{0,49}$'),
    from_status varchar(50),
    to_status   varchar(50) NOT NULL,
    guard_cel   varchar(1000),
    position    int         NOT NULL,
    UNIQUE (workflow_id, code)
);
