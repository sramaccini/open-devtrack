# The Administration area

The area *Administration* of the side menu (ADR-22) shows to whoever administers something in the tenant of the
session. Each item shows only to those with its permission. A change made here needs a login younger than 15 minutes
(`ODT_RECENT_LOGIN_MAX_AGE`): an older one asks for the password again, then the action goes on. Every change is in a
log.

## Who administers what

| Role | Where it comes from | What it gives |
| --- | --- | --- |
| `GLOBAL_ADMIN` | authz-library, a system role | Everything below in every tenant: the tenants, the global roles, turning users off and on |
| `TENANT_ADMIN` | authz-library, a system role | The users of the tenant and their roles, and ending their sessions |
| `ODT_ADMIN` | OpenDevTrack | Admin of every project of the tenant: groups, permission schemes, the administration log |
| `ODT_PROJECT_CREATOR` | OpenDevTrack | Creates projects, and becomes the admin of each one it creates |
| `ODT_MEMBER` | OpenDevTrack | Sees the projects and the users of the tenant |
| `ODT_UI_AUTHOR`, `ODT_UI_APPROVER` | OpenDevTrack | Publish and approve the screens in the registry: for the publisher of `ui/`, not for people |

What a user does in a project comes from their project role (viewer, developer, admin), or from the role of a group
they belong to, as the permission scheme of the project says. That role is given in the project, not here.

## Users

*Administration → Users* lists the users of the tenant and their roles.

- **Add user**: the username must be the one in Keycloak, where the account is made first
  ([keycloak.md](keycloak.md)). A user already known in another tenant gets the roles all the same.
- **Roles**: replaces the roles of the user in the tenant. authz-library refuses a change to one's own roles, and
  `GLOBAL_ADMIN` from an administrator who is not global (403).
- **Sessions** and **Sign out everywhere**: the sessions of the user, and ending them.
- **Turn off** and **Turn on** (global administrator): the user can no longer sign in to any tenant.
- **Export data**: a JSON file of everything OpenDevTrack keeps about the user in the tenant. It is what a request of
  access under the GDPR needs ([data.md](../security/data.md), «Export and removal»).
- **Remove from tenant**: the user loses their roles. Their name becomes a pseudonym `former-user-<n>` in every table
  of the tenant, those of authz-library included, and their messages, preferences, filters and memberships are
  deleted. The texts they wrote stay. It asks twice, and cannot be undone. The account in Keycloak is deleted there.

## Tenants and global roles

- *Tenants* (global administrator): the tenants, and *New tenant*. The code cannot change. Each tenant has its own
  projects, users and permission schemes, and nothing is shared between them (ADR-09).
- *Global roles*: what each role gives. The system roles do not change; the global administrator edits the others.

## Groups and permission schemes

- *Groups* (`ODT_ADMIN`): named sets of users of the tenant. A group is a member of a project with a role, and a
  user's role in a project is the highest of their own and those of their groups. A change that would leave a project
  without an admin is refused.
- *Permission schemes* (`ODT_ADMIN`): what each project role may do. Every tenant has a default scheme. *New* copies
  it, and each project picks its scheme in its details. Every role sees the issues, and the admin always edits the
  project and its members. A scheme in use, or the default one, cannot be removed.

## Logs

- *Administration log* (`ODT_ADMIN`): the changes of the configuration of OpenDevTrack (projects, members, groups,
  schemes, workflows, fields, webhooks, the export and removal of users), kept for `ODT_AUDIT_RETENTION`. Each project
  has the same log for itself, under *Administration log* in its details.
- *Access log*: the last entries of the log of authz-library (users, roles, tenants).

The logins, wrong passwords and locked accounts are in Keycloak, in its events ([keycloak.md](keycloak.md)).
