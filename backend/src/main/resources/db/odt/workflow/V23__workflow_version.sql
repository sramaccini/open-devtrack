-- Workflows edited by the admin of the project (ADR-20): the version of the workflow answers 412 to a change made on an
-- older copy, and the time of the last change.

ALTER TABLE odt_workflow.workflow
    ADD COLUMN version    bigint      NOT NULL DEFAULT 0,
    ADD COLUMN updated_at timestamptz;
