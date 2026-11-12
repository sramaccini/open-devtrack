-- Users of ApiTokenTest. Tenant ALPHA: mario (member and project creator), luisa (member) and the service account of
-- the client ci; nora is a member of ALPHA and BETA.
INSERT INTO authz.tenant (code, description) VALUES ('ALPHA', 'Alpha'), ('BETA', 'Beta') ON CONFLICT DO NOTHING;

INSERT INTO authz.app_user (username, email) VALUES
    ('mario', 'mario@example.org'),
    ('luisa', 'luisa@example.org'),
    ('nora', 'nora@example.org'),
    ('service-account-ci', 'ci@example.org')
ON CONFLICT DO NOTHING;

INSERT INTO authz.user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, t.tenant FROM authz.app_user u
JOIN (VALUES ('mario', 'ALPHA'), ('luisa', 'ALPHA'), ('nora', 'ALPHA'), ('nora', 'BETA'), ('service-account-ci', 'ALPHA'))
    AS t(username, tenant) ON u.username = t.username
JOIN authz.app_role r ON r.role_code = 'ODT_MEMBER' OR (r.role_code = 'ODT_PROJECT_CREATOR' AND u.username = 'mario')
ON CONFLICT DO NOTHING;
