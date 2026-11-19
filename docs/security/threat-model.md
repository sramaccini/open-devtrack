# Threat model

The main flows of OpenDevTrack, what can go wrong in each (STRIDE: spoofing, tampering, repudiation, information
disclosure, denial of service, elevation of privilege), what stops it and what is left (ASVS 1.1.2). Each ADR that
adds or changes a flow updates its section here, in the same commit; the review of each release goes through it with
[asvs.md](asvs.md). The data and their levels are in [data.md](data.md).

## The system

```text
browser ──https──▶ proxy (TLS, installation) ──▶ Quadrava runtime (nginx: pages, /bff /authz /api /registry)
                                                        │
tools, integrations ──https + Bearer──────────────────▶ │
                                                        ▼
                                  back end (Spring Boot monolith, authz-library BFF) ──▶ Keycloak (OIDC)
                                       │            │            │              └────▶ webhooks of the projects (https out)
                                       ▼            ▼            ▼                     SMTP (mail out)
                                  PostgreSQL      Redis       attachment store (database, filesystem or S3)
```

Trust boundaries:

1. **Browser and the internet → proxy and runtime.** Everything that arrives is untrusted.
2. **Runtime → back end.** The runtime forwards the BFF paths; the back end trusts `X-Forwarded-*` only from it or a
   trusted proxy (`server.forward-headers-strategy`).
3. **Back end → Keycloak, PostgreSQL, Redis, storage, SMTP.** Inside the installation's network, with TLS and its
   credentials from the secret store (asvs.md, «The installation»).
4. **Back end → webhook addresses.** Outside: chosen by a project admin, so untrusted as a destination.
5. **Tenant → tenant.** Several organisations share one installation and one database (ADR-09).

Actors: an anonymous visitor; a signed-in member of a project, with the roles of the project; a project admin; a
tenant admin and a global admin (authz-library); a tool or integration with an access token; the receiver of a
webhook; the operator of the installation.

## Flows

### Login and session

| Threat | What stops it | Left |
| --- | --- | --- |
| S: stolen or guessed credentials | Keycloak: password policy, brute force detection, OTP or WebAuthn for administrators (installation); login limited to 20 a minute per IP (authz-library) | Keycloak has no pepper and does not notify a reused OTP (O6, accepted in ADR-24) |
| S: stolen session cookie | `__Host-SESSION_ID`, HttpOnly, Secure, SameSite=Lax; HSTS; 30 minutes idle, 10 hours at most; a new session at each login | A password changed without *Sign out from other devices* leaves the other sessions (Keycloak) |
| T: cross-site request forgery | CSRF token in `X-XSRF-TOKEN` for every write and a check of `Origin` (authz-library); SameSite=Lax | |
| I: open redirect after login | The return address is checked against the allowed origins (authz-library) | |
| E: an old session keeps permissions that were removed | Permissions are resolved at login and on a tenant switch; the admin API can end a user's sessions, and a logout in Keycloak ends them through the back-channel logout | A role change does not reach a running session by itself until it ends |
| R: denying an action | Logins in authz-library and Keycloak events; refusals logged with user and tenant (`AccessRefusalLog`); history of issues and audit of the configuration | |

### Tenants and projects

| Threat | What stops it | Left |
| --- | --- | --- |
| I, E: reading or changing another tenant's data | `tenant_id` of the project from the session on every query (ADR-09); the same 404 for a resource of another tenant as for one that does not exist; tests for each controller | A missing filter in new code; row-level security as a second line is left for 1.x (`docs/search-load.md`) |
| E: a member doing what the project role does not allow | Permissions `odt.<resource>.<action>` from permission schemes and groups (ADR-07, ADR-22), checked by the back end on every call; `_perm` only hides buttons | |
| E: the last admin removing themselves, a tenant admin giving a global role | Refused by the back end (last admin) and by authz-library (403) | |

### Issues, comments, search

| Threat | What stops it | Left |
| --- | --- | --- |
| T: script in a text (stored XSS) | Texts drawn as restricted Markdown by Quadrava, no HTML; CSP of the runtime without inline scripts; `nosniff` | |
| T: injection in a search | ODQL parsed by hand and compiled with jOOQ to parameters (ADR-16) | |
| D: a guard of a workflow that never ends | CEL with at most 1,000 iterations and 1,000 characters (ADR-10) | |
| D: very large texts or lists | Limits on every field: description and comment 32,000 characters, 20 labels, 100 links, 50 custom fields a project, 100 rows a search page | |
| I: mentions telling who exists | Only members of the project are suggested and notified (ADR-13) | |

### Attachments

| Threat | What stops it | Left |
| --- | --- | --- |
| T: a file that runs in the browser | Always `Content-Disposition: attachment` with `nosniff`, never shown inline; the type declared kept only when the first bytes confirm it, otherwise `application/octet-stream` | |
| T: malware passed to other members | clamd before the file is stored, refused when infected or when clamd cannot look at it (installation) | Only when the installation runs clamd |
| D: filling the storage | 25 MB a file, 100 files an issue, a quota per project (installation) | No quota per user |
| I: reading a file of another project | The bytes go through the back end with the permissions of the issue (ADR-14) | |

### Public API and tokens

| Threat | What stops it | Left |
| --- | --- | --- |
| S: a forged or stolen token | Keycloak tokens checked by signature, issuer and expiry; personal access tokens hashed, shown once, revocable (authz-library) | |
| E: a token choosing a tenant that is not its user's | `X-Tenant` accepted only among the user's tenants | |
| D: a script calling too much | 600 requests a minute per user on the bearer path, and as many across the sessions of a user (authz-library) | |
| T: a request with unexpected fields | Every JSON body checked against the schema of its operation in the published OpenAPI (`RequestSchemas`); each controller checks what it reads | None known: a field the schema does not name is refused (`additionalProperties: false`), the screens send only the fields of their forms (Q22) |

### Webhooks

| Threat | What stops it | Left |
| --- | --- | --- |
| I, E: server-side request forgery | Only `https` addresses whose host resolves to public addresses: loopback, private, link-local and reserved ranges refused at saving and before each attempt (ADR-21) | |
| S: a forged delivery to the receiver | Signed with the secret of the webhook (Standard Webhooks); the secret encrypted at rest (ADR-23) | With the plugin `local`, `ODT_SECRET_KEY` is in the memory of the back end; `transit` keeps it in Vault (installation) |
| D: a receiver that never answers | A timeout of 10 s, no redirect followed, retries with backoff, turned off after three days of failures; at most 10 webhooks a project | |
| I: a body with data the receiver should not see | The project's events only; a comment's text is never sent (ADR-21) | |

### Administration and configuration

| Threat | What stops it | Left |
| --- | --- | --- |
| E: changing one's own roles | Refused by authz-library (403 self-modification) | |
| R: a change to the configuration without a trace | Audit log of the configuration with who and when, 2 years (ADR-22) | |
| I: the export of another user's data | `/api/me/export` always for the user of the session; the export of another user only with `authz.rbac.manage`, inside the tenant, in the audit log (O13) | |
| T: removing a user by mistake, or oneself | The row action asks twice, the name typed again is checked by the back end; nobody removes themselves; a user with global roles only by the global admin (authz-library) | Not reversible: the pseudonym keeps no link to the name |
| S, E: someone at an unattended screen doing administration | The session's timeouts; a login younger than 15 minutes before the changes of the administration, the export and removal of a user, workflows, permission schemes, groups, webhooks and personal access tokens (authz-library A8, Quadrava Q23 and Q24: Keycloak asks the password again, also before a download) | What the user typed before the new login is lost, the action is done again |

### Screens of `ui/` (registry)

| Threat | What stops it | Left |
| --- | --- | --- |
| T: a forged screen | Publishing needs a token of a user with the author role; every document validated against the schemas of the pinned Quadrava; every version kept (ADR-03, ADR-12) | |

### Mail

| Threat | What stops it | Left |
| --- | --- | --- |
| T: a mail that runs or misleads | Plain text mails only (ADR-18); links point to the installation's own address | |
| I: mails to the wrong person | Addresses from the users of the tenant; preferences per user | |

### Supply chain and operation

| Threat | What stops it | Left |
| --- | --- | --- |
| T: a vulnerable or malicious dependency, or an image not built here | Versions pinned; OSV-Scanner fails the build on a high one; Dependabot; CodeQL | The check of the signature at deploy is the installation's (`docs/operations/README.md`) |
| T, I: logs forged or leaking | Structured JSON logs (escaped values); no Secret logged | |
| D: abuse that nobody sees | Refusals logged and counted; Prometheus alert rules on refusals, 429s, logins, webhooks and the antivirus (`docs/operations/alerts.yaml`, installation) | |

## Limits of the business logic

The limits that keep one user or one project from taking more than its share (ASVS 11.1.5), all enforced by the back
end: 20 logins a minute per IP; 600 API requests a minute per user with a token, and 600 with a session; sessions of 30 minutes idle and 10
hours at most; 25 MB a file, 100 files an issue and the quota of a project; 10 webhooks a project; 50 custom fields a project; 20 labels and
100 links an issue; 32,000 characters for a description or a comment; 100 rows a search page and 500 rows a list of
history; CEL guards of at most 1,000 iterations.
