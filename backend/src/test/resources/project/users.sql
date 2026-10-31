-- Users of ProjectApiTest. Tenant ALPHA: mario (member and project creator), luisa and paola (members). Tenant BETA:
-- ugo (member and project creator), who must never see the projects of ALPHA.
INSERT INTO tenant (code, description) VALUES ('ALPHA', 'Alpha'), ('BETA', 'Beta') ON CONFLICT DO NOTHING;

INSERT INTO app_user (username, email) VALUES
    ('mario', 'mario@example.org'),
    ('luisa', 'luisa@example.org'),
    ('paola', 'paola@example.org'),
    ('ugo', 'ugo@example.org')
ON CONFLICT DO NOTHING;

INSERT INTO user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, t.tenant FROM app_user u
JOIN (VALUES ('mario', 'ALPHA'), ('luisa', 'ALPHA'), ('paola', 'ALPHA'), ('ugo', 'BETA')) AS t(username, tenant)
    ON u.username = t.username
JOIN app_role r ON r.role_code = 'ODT_MEMBER' OR (r.role_code = 'ODT_PROJECT_CREATOR' AND u.username IN ('mario', 'ugo'))
ON CONFLICT DO NOTHING;
