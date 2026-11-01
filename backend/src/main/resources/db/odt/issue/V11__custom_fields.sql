-- Custom fields of the issues (module issue, ADR-11): definitions per project and the values in a JSONB object of the
-- issue, keyed by the key of the definition. An archived definition leaves the forms; its values stay in the issues.

CREATE TABLE odt_issue.field_definition (
    id          uuid          PRIMARY KEY DEFAULT uuidv7(),
    project_id  uuid          NOT NULL,
    key         varchar(40)   NOT NULL CHECK (key ~ '^[a-z][a-z0-9_]{0,39}$'),
    label       varchar(100)  NOT NULL,
    type        varchar(20)   NOT NULL
        CHECK (type IN ('TEXT', 'NUMBER', 'DATE', 'CHECKBOX', 'SELECT', 'MULTISELECT', 'USER')),
    options     jsonb         NOT NULL DEFAULT '[]',
    required_if varchar(1000),
    position    integer       NOT NULL,
    archived    boolean       NOT NULL DEFAULT false,
    version     bigint        NOT NULL DEFAULT 0,
    UNIQUE (project_id, key)
);

ALTER TABLE odt_issue.issue ADD COLUMN custom jsonb NOT NULL DEFAULT '{}';

CREATE INDEX ix_issue_custom ON odt_issue.issue USING gin (custom);
