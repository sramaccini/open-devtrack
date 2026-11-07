-- The due date and the reviewer of an issue in the index (ADR-19), written by the listeners as the other fields. No
-- issue has them before this version, so nothing needs a reindex.
ALTER TABLE odt_search.issue_doc
    ADD COLUMN due_on   date,
    ADD COLUMN reviewer varchar(150);

CREATE INDEX ix_doc_project_due ON odt_search.issue_doc (project_id, due_on);
CREATE INDEX ix_doc_reviewer ON odt_search.issue_doc (reviewer, project_id);
