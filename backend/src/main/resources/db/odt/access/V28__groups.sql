-- Groups (ADR-22): named sets of users of a tenant that serve the projects only. A group is a member of a project with
-- a role, like a user; a user's role in a project is the highest of their own and those of their groups. The table is
-- user_group, since group is a reserved word of SQL.
CREATE TABLE odt_access.user_group (
    id          uuid         PRIMARY KEY,
    tenant_id   varchar(64)  NOT NULL,
    name        varchar(100) NOT NULL,
    description varchar(500),
    created_by  varchar(150) NOT NULL,
    created_at  timestamptz  NOT NULL,
    version     bigint       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_user_group_name ON odt_access.user_group (tenant_id, lower(name));

CREATE TABLE odt_access.group_member (
    group_id uuid         NOT NULL REFERENCES odt_access.user_group (id) ON DELETE CASCADE,
    username varchar(150) NOT NULL,
    added_by varchar(150) NOT NULL,
    added_at timestamptz  NOT NULL,
    PRIMARY KEY (group_id, username)
);

CREATE INDEX ix_group_member_user ON odt_access.group_member (username);

CREATE TABLE odt_access.project_group (
    project_id uuid         NOT NULL,
    group_id   uuid         NOT NULL REFERENCES odt_access.user_group (id) ON DELETE CASCADE,
    role       varchar(20)  NOT NULL CHECK (role IN ('VIEWER', 'DEVELOPER', 'ADMIN')),
    granted_by varchar(150) NOT NULL,
    granted_at timestamptz  NOT NULL,
    PRIMARY KEY (project_id, group_id)
);

CREATE INDEX ix_project_group_group ON odt_access.project_group (group_id);
