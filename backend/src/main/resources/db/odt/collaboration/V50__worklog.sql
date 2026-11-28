-- The work log of the issues (ADR-30): who worked, from when, how long in whole minutes, with an optional comment in the
-- restricted Markdown of the comments. The issue is only an identifier, as for the comments; the time spent of the
-- issue is the sum of its work logs, kept by the module issue as they change.
CREATE TABLE odt_collaboration.worklog (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    issue_id   uuid         NOT NULL,
    author     varchar(150) NOT NULL,
    started_at timestamptz  NOT NULL,
    minutes    integer      NOT NULL CHECK (minutes > 0),
    comment    text,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0
);

CREATE INDEX ix_worklog_issue ON odt_collaboration.worklog (issue_id, started_at);
CREATE INDEX ix_worklog_author ON odt_collaboration.worklog (author);
