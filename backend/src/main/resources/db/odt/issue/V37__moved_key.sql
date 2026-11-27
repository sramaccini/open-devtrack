-- The keys an issue had before it moved to another project (ADR-27): WEB-3 moved to APP still leads to the issue. The
-- source project is its identifier, as in odt_issue.issue; its number is never given again (the counter does not go
-- back). Gone with the issue.
CREATE TABLE odt_issue.moved_key (
    tenant_id  varchar(50) NOT NULL,
    project_id uuid        NOT NULL,
    number     bigint      NOT NULL,
    issue_id   uuid        NOT NULL REFERENCES odt_issue.issue (id) ON DELETE CASCADE,
    moved_at   timestamptz NOT NULL,
    PRIMARY KEY (project_id, number)
);

CREATE INDEX ix_moved_key_issue ON odt_issue.moved_key (issue_id);
