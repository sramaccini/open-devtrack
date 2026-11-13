# The data of OpenDevTrack

What OpenDevTrack keeps, how sensitive it is, how it is protected and how long it stays (ASVS 1.8.1, 1.8.2, 8.3.4).
It describes the code of `main`; where a period is set by the installation, the default is named. Reviewed with each
ADR that adds data, and at each release with [asvs.md](asvs.md).

## Levels

| Level | What | Protection asked |
| --- | --- | --- |
| **Secret** | Values that open access by themselves: secrets of webhooks, tokens, passwords, keys | Never logged or shown again; encrypted or hashed at rest; only the code that uses them reads them |
| **Personal** | Data about a person: user name, display name, email, what a person wrote and when, mentions, preferences | Read by the members of the project and the administrators only; inside the tenant; kept no longer than listed below; exported or removed on request (O13) |
| **Project** | The work of a tenant: issues, comments, attachments, boards, sprints, filters, reports, webhooks | Read by the members of the project, as its permissions say; never by another tenant (ADR-09) |
| **Configuration** | Workflows, fields, schemes, the screens of `ui/` | Changed by administrators, each change in the audit log (ADR-22) |

Text written by people (a description, a comment, a file) is Project data that often holds Personal data, and
sometimes a Secret pasted by mistake: it gets the protection of Personal data, and nothing in it is trusted (it is
drawn as restricted Markdown, never as HTML; a file is always downloaded, never shown).

## Where it is

| Data | Level | Where | At rest | Kept |
| --- | --- | --- | --- | --- |
| Accounts: user name, name, email, password, OTP | Secret, Personal | Keycloak | Keycloak's hashing; the installation's database | Keycloak's account life |
| Users of the tenants, roles, the RBAC audit | Personal | PostgreSQL `authz` (authz-library: `app_user`, `user_role`, `rbac_audit`) | Volume encryption of the installation | Until the user is removed |
| Personal access tokens | Secret | PostgreSQL `authz.personal_access_token` | Hashed, shown once (authz-library) | Until revoked or expired |
| Sessions: user, name, email, tenants, permissions | Personal | Redis | Not persisted beyond Redis's own; the cookie holds only an id | 30 minutes idle, 10 hours at most |
| Projects, members, groups, schemes | Project, Personal | PostgreSQL `odt_project`, `odt_access` | Volume encryption | Until removed |
| Issues, links, watchers, custom fields | Project, Personal | PostgreSQL `odt_issue` | Volume encryption | Until the issue is deleted |
| Comments and mentions | Project, Personal | PostgreSQL `odt_collaboration` | Volume encryption | With the issue |
| Attachments | Project, Personal | The `AttachmentStore` of ADR-14: database (default), filesystem or S3-compatible storage | Encryption of the volume or the bucket; SHA-256 kept | With the issue; orphan bytes swept after a day |
| History of the issues | Project, Personal | PostgreSQL `odt_audit.issue_change` | Volume encryption | With the issue |
| Audit log of the configuration | Configuration, Personal (who) | PostgreSQL `odt_audit.admin_event` | Volume encryption | 2 years (`odt.audit.retention`) |
| Messages to a user and the mail outbox | Personal | PostgreSQL `odt_notification.message` | Volume encryption | 90 days |
| Notification preferences | Personal | PostgreSQL `odt_notification.preference` | Volume encryption | Until changed |
| Search read model (copies of the texts) | Project, Personal | PostgreSQL `odt_search.issue_doc` | Volume encryption | With the issue |
| Saved filters | Project, Personal (owner) | PostgreSQL `odt_search.filter` | Volume encryption | Until removed |
| Report read models | Project | PostgreSQL `odt_reporting` | Volume encryption | Until the project is removed |
| Webhooks: address and secret | Project, Secret | PostgreSQL `odt_integration.webhook` | The secret encrypted with `ODT_SECRET_KEY` (AES-GCM, ADR-23) | Until removed |
| Webhook deliveries: the body sent, the status and the last error | Project, Personal | PostgreSQL `odt_integration.delivery` | Volume encryption | 30 days |
| Events between modules | Project | PostgreSQL `odt.event_publication` | Volume encryption | Deleted once handled |
| Screens of `ui/` | Configuration | PostgreSQL `odt_uiregistry` | Volume encryption | Every published version |
| Logs | Personal (user names, paths) | The log system of the installation | Its own | The installation's; OpenDevTrack logs no Secret, and its lines of refusals leave out the query |
| `ODT_SECRET_KEY`, `DB_PASSWORD`, `IAM_CLIENT_SECRET`, mail and storage credentials | Secret | The secret store of the platform (asvs.md, «The installation») | Its own | Rotated by the installation |

## Rules for new data

- An ADR that adds a table or a field names its level here, in the same commit.
- A Secret is encrypted with `SecretBox` or hashed, never logged, and never sent back once saved.
- Personal data gets a period or goes with the record it belongs to, and is part of the export and the removal of a
  user's data when O13 is built.
- Data copied into a read model (search, reports, deliveries) goes away with its source or within its period.

## Open

- Export and removal of a user's data on request (O13, issue #16): today an administrator removes the user from the
  tenant, while what the user wrote stays under their name.
- `ODT_SECRET_KEY` held outside the memory of the back end (O10, issue #13).
