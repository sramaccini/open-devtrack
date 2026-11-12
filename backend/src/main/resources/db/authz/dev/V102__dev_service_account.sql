-- Development data, loaded only with the profile "dev": the user of the service account of the client opendevtrack-ci
-- of the realm of e2e/authz (ADR-21), a member of ACME. A project admin adds it to a project like any user.
INSERT INTO app_user (username, email) VALUES
    ('service-account-opendevtrack-ci', 'opendevtrack-ci@example.org')
ON CONFLICT DO NOTHING;

INSERT INTO user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, 'ACME' FROM app_user u, app_role r
WHERE u.username = 'service-account-opendevtrack-ci' AND r.role_code = 'ODT_MEMBER'
ON CONFLICT DO NOTHING;
