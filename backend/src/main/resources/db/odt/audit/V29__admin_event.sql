-- Audit log of the administration (ADR-22): the changes to the configuration of a tenant and of its projects, written
-- after the commit from the events published by the modules. The project is only an identifier (no foreign key to
-- another module's schema), so the log of a project outlives it. Secrets are never written: a new secret is «rotated».
-- Kept for odt.audit.retention (2 years), then deleted by a nightly job.

CREATE TABLE odt_audit.admin_event (
    id          uuid         PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   varchar(50)  NOT NULL,
    project_id  uuid,
    action      varchar(50)  NOT NULL,
    target      varchar(200),
    actor       varchar(150) NOT NULL,
    occurred_at timestamptz  NOT NULL,
    before      jsonb        NOT NULL DEFAULT '{}',
    after       jsonb        NOT NULL DEFAULT '{}'
);

CREATE INDEX ix_admin_event_tenant ON odt_audit.admin_event (tenant_id, occurred_at DESC);
CREATE INDEX ix_admin_event_project ON odt_audit.admin_event (project_id, occurred_at DESC) WHERE project_id IS NOT NULL;
CREATE INDEX ix_admin_event_occurred ON odt_audit.admin_event (occurred_at);
