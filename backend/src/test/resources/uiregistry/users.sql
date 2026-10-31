-- Users of RegistryApiTest: two authors, an approver, an author who is also an approver, a member without registry access.
INSERT INTO tenant (code, description) VALUES ('TEST', 'Test') ON CONFLICT DO NOTHING;

INSERT INTO app_user (username, email) VALUES
    ('mario', 'mario@example.org'),
    ('luisa', 'luisa@example.org'),
    ('paola', 'paola@example.org'),
    ('carla', 'carla@example.org'),
    ('ugo', 'ugo@example.org')
ON CONFLICT DO NOTHING;

INSERT INTO user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, 'TEST' FROM app_user u JOIN app_role r ON
    (u.username IN ('mario', 'luisa', 'carla') AND r.role_code = 'ODT_UI_AUTHOR') OR
    (u.username IN ('paola', 'carla') AND r.role_code = 'ODT_UI_APPROVER') OR
    (r.role_code = 'ODT_MEMBER')
ON CONFLICT DO NOTHING;
