-- Changelog of the issues (module audit), one row per field changed, written after the commit from the events of the
-- module issue. The issue is only an identifier: no foreign key to another module's schema.

CREATE TABLE odt_audit.issue_change (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    issue_id   uuid         NOT NULL,
    author     varchar(150) NOT NULL,
    changed_at timestamptz  NOT NULL,
    field      varchar(50)  NOT NULL,
    old_value  text,
    new_value  text
);

CREATE INDEX ix_issue_change_issue ON odt_audit.issue_change (issue_id, changed_at);
