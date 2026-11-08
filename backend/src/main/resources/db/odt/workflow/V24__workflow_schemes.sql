-- Workflows by issue type (ADR-20, B): a project has several workflows, one of them the default (the one copied when
-- the project was created), and the scheme maps an issue type to another one. A type without a row uses the default.

ALTER TABLE odt_workflow.workflow DROP CONSTRAINT workflow_project_id_key;
ALTER TABLE odt_workflow.workflow ADD COLUMN is_default boolean NOT NULL DEFAULT false;
UPDATE odt_workflow.workflow SET is_default = true;

CREATE UNIQUE INDEX workflow_default ON odt_workflow.workflow (project_id) WHERE is_default;
CREATE UNIQUE INDEX workflow_name ON odt_workflow.workflow (project_id, lower(name));

CREATE TABLE odt_workflow.workflow_scheme (
    project_id  uuid        NOT NULL,
    issue_type  varchar(20) NOT NULL,
    workflow_id uuid        NOT NULL REFERENCES odt_workflow.workflow (id),
    PRIMARY KEY (project_id, issue_type)
);
CREATE INDEX workflow_scheme_workflow ON odt_workflow.workflow_scheme (workflow_id);
