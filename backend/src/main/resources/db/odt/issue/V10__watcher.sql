-- Watchers of the issues (module issue): the users told of their changes (module notification, later). The reporter
-- and the assignee watch an issue from the start; anyone who sees it can watch it or stop.

CREATE TABLE odt_issue.watcher (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    issue_id   uuid         NOT NULL REFERENCES odt_issue.issue (id) ON DELETE CASCADE,
    username   varchar(150) NOT NULL,
    created_at timestamptz  NOT NULL,
    UNIQUE (issue_id, username)
);

CREATE INDEX ix_watcher_username ON odt_issue.watcher (username);
