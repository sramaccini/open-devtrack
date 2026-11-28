-- Link types configured by the admin of the tenant (ADR-29): a code, stable and unique in the tenant, and the
-- description of each end. The four types of before (blocks, relates, duplicates, clones) are made for a tenant the
-- first time it needs them, as system rows; a link names its type by the code.
CREATE TABLE odt_issue.link_type (
    id         uuid         PRIMARY KEY,
    tenant_id  varchar(50)  NOT NULL,
    code       varchar(30)  NOT NULL,
    outward    varchar(60)  NOT NULL,
    inward     varchar(60)  NOT NULL,
    is_system  boolean      NOT NULL DEFAULT false,
    updated_by varchar(150) NOT NULL,
    updated_at timestamptz  NOT NULL,
    version    bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_link_type_code UNIQUE (tenant_id, code)
);

ALTER TABLE odt_issue.issue_link DROP CONSTRAINT issue_link_link_type_check;
ALTER TABLE odt_issue.issue_link ALTER COLUMN link_type TYPE varchar(30);
UPDATE odt_issue.issue_link SET link_type = lower(link_type);
CREATE INDEX ix_issue_link_type ON odt_issue.issue_link (link_type);
