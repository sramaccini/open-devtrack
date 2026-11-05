-- Messages for the user (module notification, ADR-13): who is told of what, kept as a kind with its values; the text is
-- written when the message is read, in the language of the reader. The project gives the tenant (ADR-09); no foreign
-- key to another module's schema. Kept 90 days.

CREATE TABLE odt_notification.message (
    id         uuid         PRIMARY KEY DEFAULT uuidv7(),
    project_id uuid         NOT NULL,
    recipient  varchar(150) NOT NULL,
    kind       varchar(20)  NOT NULL CHECK (kind IN ('mentioned', 'assigned', 'commented', 'transitioned')),
    issue_key  varchar(40)  NOT NULL,
    actor      varchar(150) NOT NULL,
    params     jsonb        NOT NULL DEFAULT '{}',
    created_at timestamptz  NOT NULL,
    read_at    timestamptz
);

CREATE INDEX ix_message_recipient ON odt_notification.message (recipient, created_at DESC);
CREATE INDEX ix_message_issue ON odt_notification.message (project_id, issue_key);
CREATE INDEX ix_message_created ON odt_notification.message (created_at);
