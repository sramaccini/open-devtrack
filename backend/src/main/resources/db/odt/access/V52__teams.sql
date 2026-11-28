-- Teams (ADR-31): named sets of users of a tenant that do the work, with a lead. A team gives no permission: an
-- issue names the team that does it. Kept apart from the groups of ADR-22, which give roles.
CREATE TABLE odt_access.team (
    id          uuid         PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    name        varchar(100) NOT NULL,
    description varchar(500),
    lead        varchar(150),
    created_by  varchar(150) NOT NULL,
    created_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_team_name ON odt_access.team (tenant_id, lower(name));
CREATE INDEX ix_team_lead ON odt_access.team (tenant_id, lead);

CREATE TABLE odt_access.team_member (
    team_id  uuid         NOT NULL REFERENCES odt_access.team (id) ON DELETE CASCADE,
    username varchar(150) NOT NULL,
    added_by varchar(150) NOT NULL,
    added_at timestamptz  NOT NULL,
    PRIMARY KEY (team_id, username)
);

CREATE INDEX ix_team_member_user ON odt_access.team_member (username);
