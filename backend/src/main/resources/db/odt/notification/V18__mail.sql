-- Email of the messages and preferences per user (ADR-18).

-- What a user wants for each kind of message: in the application or not, and email off, at once or in the daily
-- digest. Without a row the defaults of ADR-18 apply. language: the one the user last used, for their mails.
CREATE TABLE odt_notification.preference (
    tenant_id varchar(50)  NOT NULL,
    username  varchar(150) NOT NULL,
    kind      varchar(20)  NOT NULL CHECK (kind IN ('mentioned', 'assigned', 'commented', 'transitioned')),
    in_app    boolean      NOT NULL,
    email     varchar(10)  NOT NULL CHECK (email IN ('off', 'instant', 'daily')),
    language  varchar(5)   NOT NULL DEFAULT 'en',
    PRIMARY KEY (tenant_id, username, kind)
);

-- The outbox: a message to be mailed is due at email_due_at, alone (instant) or in the digest (daily); emailed_at
-- when sent; attempts counts the failures (given up at five). shown: false when the user turned the application off
-- for the kind but still wants the mail.
ALTER TABLE odt_notification.message
    ADD COLUMN shown          boolean     NOT NULL DEFAULT true,
    ADD COLUMN email_mode     varchar(10) CHECK (email_mode IN ('instant', 'daily')),
    ADD COLUMN email_due_at   timestamptz,
    ADD COLUMN emailed_at     timestamptz,
    ADD COLUMN email_attempts int         NOT NULL DEFAULT 0;

CREATE INDEX ix_message_email_due ON odt_notification.message (email_due_at) WHERE emailed_at IS NULL AND email_due_at IS NOT NULL;
