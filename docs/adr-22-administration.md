# ADR-22: Administration: users, groups, permission schemes, audit log

Status: Proposed (12 November 2026) — F5, epic E10

## Context

E10 of the project plan, a Must for 1.0, asks for the administration of users, groups, permission schemes and an
audit log. The plan puts the administration of users, global roles, tenants and sessions in screens of Quadrava over
the admin API of authz-library, in the same process.

What there is today:

- **authz-library 2.2.0** has an admin API under `/api/v1/admin` (`RbacAdminController`):
  - permissions, roles (create, change, remove; the system roles cannot change);
  - tenants, the users of a tenant and their roles, a user (create, change, turn off);
  - its own audit log (`rbac_audit`, `GET /api/v1/admin/audit`) of those changes;
  - all guarded by its own permissions: managing a tenant, or every tenant for the global admin.
  - It has no groups.
- **OpenDevTrack** has no administration screen. Users, roles and tenants are changed in the database or in
  Keycloak.
  - Project roles are three fixed ones (`ProjectRole`: viewer, developer, admin), each with a fixed set of
    permissions; the admin of the tenant (`odt.project.admin`) is admin of every project (ADR-07).
  - The module `audit` keeps only the history of the issues: who changed which field, and attachments.
  - The changes to the configuration (projects, members, workflows, boards, custom fields, webhooks) leave no trace
    other than `updated_at`. Most of them publish no event.

## Decision

### Users, roles and tenants

1. An **«Administration»** area in `navigation.json`, shown to whoever holds the admin permission of the tenant of
   authz-library. Its screens call the admin API of authz-library as it is, with the session; OpenDevTrack writes no
   security code for it (AGENTS.md § 2):
   - **Users** of the tenant: a list with search; add a user (user name, email); turn one off or on; assign the
     global roles of OpenDevTrack (`ODT_MEMBER`, `ODT_PROJECT_CREATOR`, `ODT_ADMIN`, `ODT_UI_AUTHOR`,
     `ODT_UI_APPROVER`).
   - **Roles**: the global roles with their permissions, read only for the system ones.
   - **Tenants**: only for the global admin.
   - **Audit of access**: the log of authz-library (`/api/v1/admin/audit`).
   - Passwords and sign-in stay in Keycloak: the screen says so, with a link to its console for the tenant.
2. «Sign out everywhere» is a row action of the user list. It calls `DELETE /bff/admin/users/{username}/sessions` of
   authz-library, which keeps its own permission for revoking sessions.

### Groups

3. A **group** is a named set of users of the tenant, kept by OpenDevTrack in the module `access`
   (`odt_access.group`, `group_member`). It serves the projects only:
   - A group can be a member of a project with a role, like a user. A user's role in a project is the highest of
     their own and those of their groups.
   - Mentions and assignments stay per user.
   - Global roles stay per user in authz-library. Groups for global roles would be security code: if they are
     needed after 1.0, they are asked there.
   - Groups are managed by the admin of the tenant (`odt.project.admin`) in the Administration area. Removing a
     group removes its project memberships, never the last admin of a project (refused with 409, as today).

### Permission schemes

4. A **permission scheme** says which permissions each of the three project roles gives. The roles stay three, with
   the names they have; what changes is their content.
   - The **tenant** owns its schemes (`odt_access.permission_scheme`), edited by the admin of the tenant.
   - A **default scheme** has today's sets and serves every project that has no other scheme. A project admin picks
     the scheme of the project among those of the tenant, in `project-detail`.
   - Only the project permissions of the catalogue can be in a scheme (`odt.issue.*`, `odt.sprint.manage`,
     `odt.project.edit`, `odt.member.manage`).
   - Some rules keep a project usable:
     - every role has `odt.issue.browse`;
     - the role admin always has `odt.project.edit` and `odt.member.manage`.
   - `ProjectAccess.permissions` reads the scheme of the project, cached in memory per scheme and dropped when it
     changes. Nothing changes for the callers, `_perm` or the checks of A1 (`ProjectPermissions`).
   - A scheme in use cannot be removed (409).

### Audit log

5. An **audit log of the administration** in the module `audit` (`odt_audit.admin_event`):
   - **What is logged**: who, when, tenant, project (if any), what (`project.created`, `member.added`,
     `member.role_changed`, `member.removed`, `group.*`, `scheme.*`, `workflow.changed`, `board.*`,
     `customfield.*`, `webhook.*`, `project.deleted`) and the values before and after. Secrets are never logged:
     the secret of a webhook is logged as «rotated».
   - **How it is written**: from events published by the modules after the commit (`shared`); the modules that have
     no such event today get one.
   - **Screens**:
     - for the admin of the tenant, in the Administration area: the log of the tenant, filtered by project, user,
       kind and dates;
     - for a project admin, in `project-detail`: the log of the project.
   - **Retention**: kept for 2 years, then deleted by a nightly job; `odt.audit.retention` changes it.
   - The history of the issues stays as it is: it is a feature of the issue, not an administration log.

## Alternatives considered

- **Groups in Keycloak, read through the token.** Keycloak has groups, but OpenDevTrack would read them from the
  token or its admin API. That ties the permissions to one identity provider, while the plan keeps authz-library as
  the only source of roles.
- **Groups in authz-library now.** That is the right place if groups carry global roles. For 1.0 they only carry
  project roles, which are OpenDevTrack's (ADR-07), so they live in `access`.
- **Project roles defined by the tenant** (any number, any name), as Jira does. More freedom, but every screen and
  test that names viewer, developer and admin would change, and the plan asks only for schemes. Possible in 1.x on
  top of the schemes.
- **One audit table for everything**, the issue history included. The issue history is read on every issue page and
  has its own shape. The administration log is read rarely and by few people.
- **The audit log of authz-library for the configuration of OpenDevTrack too.** It is the log of authz-library's own
  changes, and writing to it from the application would be using its tables as ours.

## Consequences

- `ui/`:
  - the Administration area: users, roles, tenants, audit of access, groups, permission schemes, audit log;
  - a scheme selector and the project log in `project-detail`;
  - texts in English and Italian.
- `access`: groups and schemes, with migrations. The current fixed sets become the rows of the default scheme of
  every tenant. Project membership also counts through groups (lists of members show groups as rows of their own).
- `audit`: `admin_event`, its listeners, its API and its retention job. New events in `shared` or in the modules for
  members, projects, workflows, boards, custom fields and webhooks.
- `TenantUsers` (now on `TenantDirectory`, A5) also serves the group pickers.
- Tests:
  - the admin area seen and refused by role;
  - a group's role in a project and the highest-role rule;
  - a scheme that takes `odt.issue.delete` from the developers, seen in `_perm`, in the API (403) and in
    `/authz/check`;
  - the lockout rules;
  - every logged action, with no secret in the log.
- No request to the dependencies: authz-library 2.2.0 has the admin API and the revocation of sessions, and Quadrava
  has the components (tables, forms, dialogs, tabs).
