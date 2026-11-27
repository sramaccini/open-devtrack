-- The parent of an issue and the type subtask (ADR-28): an epic is the parent of stories, tasks and bugs, which are
-- the parents of subtasks, in the same project. The module keeps the rules and deletes or detaches the children with
-- their events; SET NULL only keeps the bulk deletes of a project or a tenant possible.
ALTER TABLE odt_issue.issue DROP CONSTRAINT issue_type_check;
ALTER TABLE odt_issue.issue
    ADD CONSTRAINT issue_type_check CHECK (type IN ('TASK', 'BUG', 'STORY', 'EPIC', 'SUBTASK'));

ALTER TABLE odt_issue.issue
    ADD COLUMN parent_id uuid REFERENCES odt_issue.issue (id) ON DELETE SET NULL;

CREATE INDEX ix_issue_parent ON odt_issue.issue (parent_id) WHERE parent_id IS NOT NULL;
