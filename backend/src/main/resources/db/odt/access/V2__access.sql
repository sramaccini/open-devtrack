-- Project roles (module access): who has which role in which project. The project is only an identifier: no foreign
-- key to another module's schema (plan, data model).

CREATE TABLE odt_access.project_member (
    project_id uuid         NOT NULL,
    username   varchar(150) NOT NULL,
    role       varchar(20)  NOT NULL CHECK (role IN ('VIEWER', 'DEVELOPER', 'ADMIN')),
    granted_by varchar(150) NOT NULL,
    granted_at timestamptz  NOT NULL,
    PRIMARY KEY (project_id, username)
);

CREATE INDEX ix_project_member_user ON odt_access.project_member (username);
