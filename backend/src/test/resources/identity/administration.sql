-- A user of AdministrationApiTest known only in BETA: the admin of ALPHA adds her there too. Her own, so that the
-- users of the other tests keep their tenants.
INSERT INTO authz.app_user (username, email) VALUES ('vera', 'vera@example.org') ON CONFLICT DO NOTHING;

INSERT INTO authz.user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, 'BETA' FROM authz.app_user u JOIN authz.app_role r ON r.role_code = 'ODT_MEMBER'
WHERE u.username = 'vera'
ON CONFLICT DO NOTHING;
