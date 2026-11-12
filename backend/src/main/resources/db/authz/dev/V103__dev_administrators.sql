-- Development data, loaded only with the profile "dev": the administrators of the Administration area (ADR-22).
-- mario.rossi administers the users of ACME (TENANT_ADMIN of authz-library); anna.bianchi is the global admin, who
-- also sees the tenants and edits the roles.
INSERT INTO user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, t.tenant FROM app_user u
JOIN (VALUES ('mario.rossi', 'TENANT_ADMIN', 'ACME'), ('anna.bianchi', 'GLOBAL_ADMIN', 'GLOBEX')) AS t(username, role, tenant)
    ON u.username = t.username
JOIN app_role r ON r.role_code = t.role
ON CONFLICT DO NOTHING;
