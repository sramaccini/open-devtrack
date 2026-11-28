-- The estimates and the time spent of an issue in the index (ADR-30), in minutes, written by the listeners as the
-- other fields. They came in the same version as the work log, so no issue needs a reindex.
ALTER TABLE odt_search.issue_doc
    ADD COLUMN original_estimate integer,
    ADD COLUMN remaining_estimate integer,
    ADD COLUMN time_spent integer NOT NULL DEFAULT 0;
