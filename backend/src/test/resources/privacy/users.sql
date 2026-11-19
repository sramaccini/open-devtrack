-- A user of PrivacyApiTest in ALPHA, her own because the test removes her from the tenant: each test gives her back
-- the role of member.
INSERT INTO authz.app_user (username, email) VALUES ('rosa', 'rosa@example.org') ON CONFLICT DO NOTHING;

INSERT INTO authz.user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, 'ALPHA' FROM authz.app_user u JOIN authz.app_role r ON r.role_code = 'ODT_MEMBER'
WHERE u.username = 'rosa'
ON CONFLICT DO NOTHING;
