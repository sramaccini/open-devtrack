-- The keys an issue had before it moved (ADR-27), so that ODQL key = WEB-3 still finds it.
ALTER TABLE odt_search.issue_doc ADD COLUMN moved_keys text[] NOT NULL DEFAULT '{}';
