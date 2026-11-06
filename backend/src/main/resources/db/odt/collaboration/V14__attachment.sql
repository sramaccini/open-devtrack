-- Attachments of the issues (module collaboration, ADR-14). The metadata are always here; the bytes are in the store
-- chosen by odt.attachments.store, named by store. The key of the bytes is <project_id>/<id>: the filename given by
-- the user never reaches a path or an object name. As for the comments, no foreign key to the schema of issue.

CREATE TABLE odt_collaboration.attachment (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    issue_id    uuid         NOT NULL,
    project_id  uuid         NOT NULL,
    filename    varchar(255) NOT NULL,
    media_type  varchar(255) NOT NULL,
    size        bigint       NOT NULL CHECK (size >= 0),
    sha256      varchar(64)  NOT NULL,
    store       varchar(20)  NOT NULL,
    uploaded_by varchar(150) NOT NULL,
    uploaded_at timestamptz  NOT NULL
);

CREATE INDEX ix_attachment_issue ON odt_collaboration.attachment (issue_id, uploaded_at);

-- The bytes of the store database (the default). Keyed like the other stores, without a foreign key: the metadata
-- are removed first and the bytes after the commit, and the daily sweep removes what a failure left behind.
CREATE TABLE odt_collaboration.attachment_content (
    key        varchar(80) PRIMARY KEY,
    content    bytea       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
