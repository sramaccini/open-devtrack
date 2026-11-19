-- The pseudonyms of the users removed from a tenant (ADR-24, O13): former-user-1, former-user-2… The counter only;
-- which user got which pseudonym is not kept anywhere.
CREATE TABLE odt_privacy.pseudonym_counter (
    tenant_id varchar(50) PRIMARY KEY,
    last      bigint      NOT NULL
);
