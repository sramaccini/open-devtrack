-- Development data: who writes and who approves the screen definitions of ui/ in the registry (module uiregistry).
-- mario.rossi publishes, anna.bianchi approves (four eyes, ADR-03). The permissions are per tenant, and the tools resolve
-- them on the tenant the user last worked on: anna.bianchi gets the role in both her tenants.
INSERT INTO app_role (role_code, description) VALUES
    ('ODT_UI_AUTHOR', 'Publishes screen definitions'),
    ('ODT_UI_APPROVER', 'Approves screen definitions')
ON CONFLICT (role_code) DO NOTHING;

INSERT INTO user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, 'ACME' FROM app_user u, app_role r
WHERE u.username = 'mario.rossi' AND r.role_code = 'ODT_UI_AUTHOR';

INSERT INTO user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, t.code FROM app_user u, app_role r, (VALUES ('ACME'), ('GLOBEX')) AS t(code)
WHERE u.username = 'anna.bianchi' AND r.role_code = 'ODT_UI_APPROVER';
