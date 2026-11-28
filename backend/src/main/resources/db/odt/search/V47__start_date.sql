-- The start date of an issue in the index, written by the listeners as the other fields. No issue has one before this
-- version, so nothing needs a reindex.
ALTER TABLE odt_search.issue_doc ADD COLUMN start_on date;
