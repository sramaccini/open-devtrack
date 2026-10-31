-- Development data, loaded only with the profile "dev". Users and tenants match the realm of e2e/authz.
-- It runs after the repeatable catalogue only on a database where the catalogue already exists: versioned
-- migrations run before repeatable ones, so the roles are created here too if missing.
INSERT INTO tenant (code, description) VALUES
    ('ACME', 'Acme (development)'),
    ('GLOBEX', 'Globex (development)')
ON CONFLICT DO NOTHING;

INSERT INTO app_role (role_code, description) VALUES
    ('ODT_MEMBER', 'Member of the tenant: sees its projects and users'),
    ('ODT_PROJECT_CREATOR', 'Creates projects')
ON CONFLICT (role_code) DO NOTHING;

INSERT INTO app_user (username, email) VALUES
    ('mario.rossi', 'mario.rossi@example.org'),
    ('anna.bianchi', 'anna.bianchi@example.org');

-- mario.rossi: one tenant, member and project creator
INSERT INTO user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, 'ACME' FROM app_user u, app_role r
WHERE u.username = 'mario.rossi' AND r.role_code IN ('ODT_MEMBER', 'ODT_PROJECT_CREATOR');

-- anna.bianchi: two tenants, member in both (ADR-08: login on the last tenant used, switch with POST /bff/tenant)
INSERT INTO user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, t.code FROM app_user u, app_role r, (VALUES ('ACME'), ('GLOBEX')) AS t(code)
WHERE u.username = 'anna.bianchi' AND r.role_code = 'ODT_MEMBER';
