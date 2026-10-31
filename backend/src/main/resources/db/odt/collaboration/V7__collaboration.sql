-- Comments of the issues (module collaboration), in restricted Markdown. The issue is only an identifier: no foreign
-- key to another module's schema; the comments of a deleted issue are removed when its event arrives.

CREATE TABLE odt_collaboration.comment (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    issue_id   uuid         NOT NULL,
    author     varchar(150) NOT NULL,
    body       text         NOT NULL,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0
);

CREATE INDEX ix_comment_issue ON odt_collaboration.comment (issue_id, created_at);
