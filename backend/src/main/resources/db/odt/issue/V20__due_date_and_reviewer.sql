-- The due date and the reviewer of an issue (ADR-19): both optional; the reviewer is a member of the project, never
-- the assignee (checked by the application). The index serves «Overdue» and the issues of a project by due date.
ALTER TABLE odt_issue.issue
    ADD COLUMN due_on   date,
    ADD COLUMN reviewer varchar(150);

CREATE INDEX ix_issue_project_due ON odt_issue.issue (project_id, due_on);
CREATE INDEX ix_issue_reviewer ON odt_issue.issue (reviewer);
