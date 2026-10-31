-- Global permissions and roles of OpenDevTrack in the catalogue of authz-library (ADR-07).
-- Repeatable: it runs after the versioned migrations of authz-library and again whenever this file changes, so it
-- only adds (ON CONFLICT DO NOTHING). A permission or a role is removed by a versioned migration of its own.
-- Project roles (Developer in ODT, ...) are not here: they belong to the module access.

INSERT INTO app_permission (permission_code, description) VALUES
    ('odt.project.browse', 'See the projects of the tenant'),
    ('odt.project.create', 'Create a project'),
    ('odt.project.admin', 'Administer every project of the tenant, as the role admin of each (ADR-07)'),
    ('odt.user.browse', 'List the users of the tenant, for assignments and mentions'),
    ('odt.uidefinition.publish', 'Publish screen definitions to the registry'),
    ('odt.uidefinition.approve', 'Approve or reject screen definitions in the registry')
ON CONFLICT (permission_code) DO NOTHING;

-- Permissions held through a project role (module access, ProjectRole): registered here so that every odt.* code is in
-- the catalogue, but granted per project by OpenDevTrack, not by a global role.
INSERT INTO app_permission (permission_code, description) VALUES
    ('odt.issue.browse', 'See the issues of a project'),
    ('odt.issue.create', 'Create issues in a project'),
    ('odt.issue.edit', 'Edit the issues of a project'),
    ('odt.issue.transition', 'Move the issues of a project through their workflow'),
    ('odt.issue.comment', 'Comment on the issues of a project'),
    ('odt.issue.delete', 'Delete issues of a project'),
    ('odt.project.edit', 'Edit the details of a project'),
    ('odt.member.manage', 'Add and remove the members of a project and change their roles')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO app_role (role_code, description) VALUES
    ('ODT_MEMBER', 'Member of the tenant: sees its projects and users'),
    ('ODT_PROJECT_CREATOR', 'Creates projects'),
    ('ODT_ADMIN', 'Admin of the tenant: creates and administers every project'),
    ('ODT_UI_AUTHOR', 'Publishes screen definitions'),
    ('ODT_UI_APPROVER', 'Approves screen definitions')
ON CONFLICT (role_code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM app_role r JOIN app_permission p ON
    (r.role_code = 'ODT_MEMBER'          AND p.permission_code IN ('odt.project.browse', 'odt.user.browse')) OR
    (r.role_code = 'ODT_PROJECT_CREATOR' AND p.permission_code IN ('odt.project.create')) OR
    (r.role_code = 'ODT_ADMIN'           AND p.permission_code IN ('odt.project.browse', 'odt.user.browse', 'odt.project.create',
                                                                   'odt.project.admin')) OR
    (r.role_code = 'ODT_UI_AUTHOR'       AND p.permission_code IN ('odt.uidefinition.publish')) OR
    (r.role_code = 'ODT_UI_APPROVER'     AND p.permission_code IN ('odt.uidefinition.approve'))
ON CONFLICT DO NOTHING;
