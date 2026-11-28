-- The team of an issue in the index (ADR-31), by its identifier: ODQL names teams, which become identifiers when the
-- query is compiled, so renaming a team needs no reindex. No issue has one before this version.
ALTER TABLE odt_search.issue_doc ADD COLUMN team_id uuid;

CREATE INDEX ix_doc_team ON odt_search.issue_doc (tenant_id, team_id) WHERE team_id IS NOT NULL;
