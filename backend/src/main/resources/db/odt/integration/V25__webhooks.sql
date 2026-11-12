-- Outgoing webhooks of a project (ADR-21, B): where to send which events, signed with a secret of each webhook.
CREATE TABLE odt_integration.webhook (
    id              uuid         PRIMARY KEY,
    tenant_id       varchar(64)  NOT NULL,
    project_id      uuid         NOT NULL,
    name            varchar(100) NOT NULL,
    url             varchar(2000) NOT NULL,
    -- The event types it wants, or {*} for all
    events          text[]       NOT NULL,
    -- whsec_<base64>, as Standard Webhooks: shown once, kept to sign. After a rotation the previous one signs too,
    -- until previous_until.
    secret          varchar(100) NOT NULL,
    previous_secret varchar(100),
    previous_until  timestamptz,
    active          boolean      NOT NULL DEFAULT true,
    -- When the deliveries given up in a row began: three days of them turn the webhook off
    failing_since   timestamptz,
    created_by      varchar(255) NOT NULL,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         bigint       NOT NULL DEFAULT 0
);

CREATE INDEX ix_webhook_project ON odt_integration.webhook (project_id);
CREATE UNIQUE INDEX ux_webhook_name ON odt_integration.webhook (project_id, lower(name));

-- The outbox: one row per event and webhook, posted by the scheduled job until a 2xx or given up. The body is kept as
-- it was signed; its id is the webhook-id of every attempt.
CREATE TABLE odt_integration.delivery (
    id          uuid         PRIMARY KEY,
    webhook_id  uuid         NOT NULL REFERENCES odt_integration.webhook (id) ON DELETE CASCADE,
    event       varchar(40)  NOT NULL,
    payload     text         NOT NULL,
    -- pending, delivered, failed (given up)
    status      varchar(16)  NOT NULL,
    attempts    integer      NOT NULL DEFAULT 0,
    next_at     timestamptz,
    last_code   integer,
    last_error  varchar(300),
    last_at     timestamptz,
    created_at  timestamptz  NOT NULL
);

CREATE INDEX ix_delivery_due ON odt_integration.delivery (next_at) WHERE status = 'pending';
CREATE INDEX ix_delivery_webhook ON odt_integration.delivery (webhook_id, created_at DESC);
CREATE INDEX ix_delivery_created ON odt_integration.delivery (created_at);
