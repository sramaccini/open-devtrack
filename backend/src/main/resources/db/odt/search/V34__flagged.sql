-- The flag of an issue in the index, as Jira's Flagged: «Impediment» or empty. Written by the listeners as the other
-- fields; the issues flagged before this version take it from odt_issue.issue now, so no reindex is needed.
ALTER TABLE odt_search.issue_doc
    ADD COLUMN flagged varchar(20);

UPDATE odt_search.issue_doc d
SET flagged = 'Impediment'
FROM odt_issue.issue i
WHERE i.id = d.issue_id
  AND i.flagged;

CREATE INDEX ix_doc_flagged ON odt_search.issue_doc (project_id) WHERE flagged IS NOT NULL;
