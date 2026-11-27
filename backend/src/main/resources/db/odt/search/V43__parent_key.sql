-- The key of the parent of an issue (ADR-28), so that ODQL parent = WEB-1 finds its children. Written by the
-- listeners as the other fields; no issue had a parent before this version.
ALTER TABLE odt_search.issue_doc ADD COLUMN parent_key varchar(60);

CREATE INDEX ix_doc_parent ON odt_search.issue_doc (parent_key) WHERE parent_key IS NOT NULL;
