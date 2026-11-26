-- The flag and the votes of an issue, as in Jira. The flag marks an issue as impeded; whoever edits the issue adds or
-- removes it. The votes say who wants an issue done: anyone who sees it votes once, or takes the vote back.

ALTER TABLE odt_issue.issue
    ADD COLUMN flagged boolean NOT NULL DEFAULT false;

CREATE TABLE odt_issue.vote (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    issue_id   uuid         NOT NULL REFERENCES odt_issue.issue (id) ON DELETE CASCADE,
    username   varchar(150) NOT NULL,
    created_at timestamptz  NOT NULL,
    UNIQUE (issue_id, username)
);

CREATE INDEX ix_vote_username ON odt_issue.vote (username);
