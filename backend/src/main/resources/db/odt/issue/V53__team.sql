-- The Team of an issue (ADR-31): a team of access by its identifier, without a foreign key across modules. A team
-- deleted leaves its issues without a team (TeamDeleted).
ALTER TABLE odt_issue.issue ADD COLUMN team_id uuid;

CREATE INDEX ix_issue_team ON odt_issue.issue (tenant_id, team_id) WHERE team_id IS NOT NULL;
