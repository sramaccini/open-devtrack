# The data of OpenDevTrack

What OpenDevTrack keeps, how sensitive it is, how it is protected and how long it stays (ASVS 1.8.1, 1.8.2, 8.3.4).
It describes the code of `main`; where a period is set by the installation, the default is named. Reviewed with each
ADR that adds data, and at each release with [asvs.md](asvs.md).

## Levels

| Level | What | Protection asked |
| --- | --- | --- |
| **Secret** | Values that open access by themselves: secrets of webhooks, tokens, passwords, keys | Never logged or shown again; encrypted or hashed at rest; only the code that uses them reads them |
| **Personal** | Data about a person: user name, display name, email, what a person wrote and when, mentions, preferences | Read by the members of the project and the administrators only; inside the tenant; kept no longer than listed below; exported or removed on request («Export and removal») |
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
| Webhooks: address and secret | Project, Secret | PostgreSQL `odt_integration.webhook` | The secret encrypted with `ODT_SECRET_KEY` (AES-GCM, ADR-23) or by the transit engine of Vault (ADR-24) | Until removed |
| Webhook deliveries: the body sent, the status and the last error | Project, Personal | PostgreSQL `odt_integration.delivery` | Volume encryption | 30 days |
| Events between modules | Project | PostgreSQL `odt.event_publication` | Volume encryption | Deleted once handled |
| Pseudonym counter of the users removed | Configuration | PostgreSQL `odt_privacy.pseudonym_counter` | Volume encryption | The last number per tenant; never which user had which |
| Screens of `ui/` | Configuration | PostgreSQL `odt_uiregistry` | Volume encryption | Every published version |
| Logs | Personal (user names, paths) | The log system of the installation | Its own | The installation's; OpenDevTrack logs no Secret, and its lines of refusals leave out the query |
| `ODT_SECRET_KEY`, `DB_PASSWORD`, `IAM_CLIENT_SECRET`, mail and storage credentials | Secret | The secret store of the platform (asvs.md, «The installation») | Its own | Rotated by the installation |

## Rules for new data

- An ADR that adds a table or a field names its level here, in the same commit.
- A Secret is encrypted with `SecretBox` or hashed, never logged, and never sent back once saved.
- Personal data gets a period or goes with the record it belongs to, and is part of the export and the removal of a
  user's data: the module that stores it implements `PersonalData` (module `privacy`), and `PrivacyApiTest` checks
  that no row keeps the name after a removal.
- Data copied into a read model (search, reports, deliveries) goes away with its source or within its period.

## Export and removal

ADR-24 point 7, O13, ASVS 8.3.2. Each module that keeps user names implements `PersonalData` on its own tables; the
module `privacy` asks them all, in one transaction for a removal.

- **Export.** `GET /api/me/export` (the screen «Your data») gives the signed-in user a JSON file of what OpenDevTrack
  keeps about them in the tenant; `GET /api/admin/users/{username}/export` (the row action «Export data» of
  `admin-users`) gives the same to an administrator of the tenant (`authz.rbac.manage`). Both are in the audit log
  of the configuration (`user.exported`). The file has: the profile (user name and email); the issues they reported,
  are assigned or review; their watches, votes and links; their comments, the metadata of their attachments (not the bytes,
  which are the project's and are read in the issue) and the comments that mention them; their changes in the
  histories; their actions in the audit log; their messages and preferences; their saved filters; their roles in
  projects and their groups; the projects they lead or made; the webhooks they made, without secrets.
- **Removal.** `POST /api/admin/users/{username}/remove` with the name typed again (the row action «Remove from
  tenant» asks twice, then ends the user's sessions). Refused for the administrator themselves, and by authz-library
  for a user with global roles unless the administrator is global. In one transaction:
  - the user loses every role in the tenant (authz-library);
  - in the RBAC audit of the tenant (authz-library 2.6.0, A10) the name becomes the pseudonym, as actor, target and
    in the values; with no role left in any tenant the user is deleted from `authz` (their email and personal
    access tokens), and the name leaves the entries without a tenant too; authz-library logs `USER_FORGOTTEN`.
    A tenant administrator does not delete a user that a global administrator deactivated: that row stays;
  - deleted: their messages, mails not sent yet, preferences, saved filters, watches, votes, mentions, memberships of
    projects and groups;
  - the name becomes the pseudonym `former-user-<n>` of the tenant (a counter, without a record of who it was) as
    reporter, assignee, reviewer and in custom fields of type user, author of comments and attachments, author and
    values of the histories, creator of links, groups, projects and webhooks, lead, granter of roles, actor, target
    and values of the audit log, actor in the messages to others, in the search and report read models and in the
    bodies of the deliveries still kept. A form opened before gets a 412: the version of the issue or comment moves;
  - the audit log gets `user.removed` with the pseudonym only.
- **What stays.**
  - The texts they wrote, and their name inside a text (a mention, a filter of a board): project data, the project's
    to edit. The screen says so.
  - Outside the tables of OpenDevTrack: the account in Keycloak (the installation removes it there); the row of the
    user in `authz` while they have roles in another tenant (the pseudonym of each tenant is its own); mails already sent; bodies already delivered to webhook receivers; the logs of the installation,
    within their own period.
  - An export asked after the removal is logged under the name the administrator typed.
