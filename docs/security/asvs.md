# OWASP ASVS 4.0.3, level 2

The state of every level 2 requirement of the [Application Security Verification Standard
4.0.3](https://github.com/OWASP/ASVS/tree/v4.0.3) (CC BY-SA 4.0), as ADR-23 asks. The requirements are named by number
with a short summary; the standard has the full text.

Reviewed on 13 November 2026 against `main` (authz-library 2.2.0, Quadrava 0.15.0, Keycloak 26.8). It is reviewed
again at each release.

## States

| State | Meaning |
| --- | --- |
| **Met** | Where: OpenDevTrack (`odt`), authz-library (`authz`), the Quadrava runtime (`quadrava`), Keycloak (`kc`) |
| **Install** | Met by the installation, as [the installation](#the-installation) below asks; OpenDevTrack cannot do it alone |
| **N/A** | Not applicable, with the reason |
| **Open** | Not met yet: an item of [Open items](#open-items), with its issue |

Level 2 has 259 requirements. Today: 185 met, 32 met by the installation, 24 not applicable, 18 open (10 items).

## The installation

What an installation must do for the requirements marked **Install**:

- **TLS** in front of the runtime and on every connection between the parts (PostgreSQL, Redis, Keycloak, SMTP, the
  object storage). Only TLS 1.2 and 1.3, strong suites, trusted certificates, OCSP stapling at the proxy. The
  development stack and the e2e run on plain HTTP and are not an installation (`SECURITY.md`).
- **Keycloak realm**:
  - password policy: `length(12)`, `maxLength(128)`, `notUsername`, `passwordBlacklist` (a list of breached passwords),
    no composition rules, no expiry;
  - brute force detection on;
  - OTP or WebAuthn required for the administrators (`ODT_ADMIN`, the tenant administrators of authz-library);
  - mails on credential changes (`UPDATE_PASSWORD`, `UPDATE_TOTP` events with the email event listener);
  - the login, admin and error events saved and sent to the log system;
  - no default accounts; the realm of `e2e/authz` is for development only.
- **Database**: the role of the back end owns its database and is neither a superuser nor `BYPASSRLS` (the row-level
  security of 1.x needs it, `docs/search-load.md`). The volume is encrypted.
- **Secrets**: `ODT_SECRET_KEY`, `DB_PASSWORD`, `IAM_CLIENT_SECRET` and the mail and storage credentials come from a
  secret store of the platform (Kubernetes secrets, a vault), never from files in the image.
- **Logs**: the logs of the back end, the runtime and Keycloak go to a central system, kept away from the hosts, with
  UTC clocks synchronised by NTP.
- **HSTS**: `QUADRAVA_HSTS` set on the runtime, for example `max-age=31536000; includeSubDomains`, or the same header at
  the proxy that ends TLS.
- **Images from other hosts**: none by default (`img-src 'self' data:`); `QUADRAVA_CSP_IMG_SRC` adds hosts when an
  installation needs them. The screens of OpenDevTrack and its Markdown show none.
- **Network**: PostgreSQL, Redis and the back end are not reachable from outside; only the runtime is published.

## V1 Architecture, design and threat modeling

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 1.1.1 | Secure development lifecycle | Met | odt: ADRs for each decision (`docs/`), review of each change, CI with tests, CodeQL, OSV-Scanner, ZAP and axe-core |
| 1.1.2 | Threat modeling for design changes | Met | odt: [threat-model.md](threat-model.md), updated with each ADR that changes a flow |
| 1.1.3 | Security constraints in user stories | Met | odt: each ADR states who may do what (project roles, permission schemes, ADR-07, ADR-22) |
| 1.1.4 | Trust boundaries, components, data flows documented | Met | odt: `README.md`, `docs/check-authz-end-to-end.md` (the parts and their links), ADR-03, ADR-09 |
| 1.1.5 | High-level architecture and remote services analysed | Met | odt: the ADRs of the parts (Quadrava, authz-library, Keycloak, PostgreSQL, Redis, S3, SMTP, webhooks) |
| 1.1.6 | Central, vetted, reusable security controls | Met | authz: login, session, CSRF, roles; odt: `platform/CurrentUser`, `access/ProjectAccess` as the one way in |
| 1.1.7 | Secure coding checklist for developers | Met | odt: `AGENTS.md`, `CONTRIBUTING.md` and this list |
| 1.2.1 | Low-privilege OS accounts | Met | odt: the back end runs as user `odt` (`backend/Dockerfile`); quadrava: nginx runs as user 101 in group 0 (0.14.0) |
| 1.2.2 | Communications between components authenticated | Install | The passwords of PostgreSQL, Redis, SMTP, storage; the client secret to Keycloak |
| 1.2.3 | One vetted authentication mechanism | Met | authz + kc: OpenID Connect with PKCE, the BFF keeps the tokens |
| 1.2.4 | No weaker alternative authentication path | Met | authz: the session and the bearer path (`authz.bff.bearer`) take the same permissions; no local login |
| 1.4.1 | Access control enforced on trusted points | Met | odt: every check on the server; the screens only hide what the server refuses anyway |
| 1.4.4 | One well-vetted access control mechanism | Met | authz: global permissions; odt: `ProjectAccess` and the permission schemes (ADR-22) |
| 1.4.5 | Feature or data based access control | Met | odt: permissions per project and per issue (`_perm`, `ProjectPermissions`, ADR-07) |
| 1.5.1 | Input and output requirements per data type | Met | odt: the ADRs give the fields, their limits and who may read them; ADR-11 for custom fields |
| 1.5.2 | No serialization with untrusted clients | Met | odt: JSON only; the session is serialized on Redis by the server, never sent to the browser |
| 1.5.3 | Input validation on a trusted layer | Met | odt: in the controllers of the back end, whatever the screens check |
| 1.5.4 | Output encoding close to the interpreter | Met | quadrava: Angular binding; odt: JSON and bound SQL |
| 1.6.1 | Key management policy | Met | odt: `backend/README.md` (generation, length, id, rotation of `ODT_SECRET_KEY`) |
| 1.6.2 | Key material in vaults | Install | The secret store of the platform |
| 1.6.3 | Keys replaceable, data re-encrypted | Met | odt: key ids and `odt.secrets.previous-keys`; secrets resealed at start (`integration/SecretsAtRest`) |
| 1.6.4 | No client-side secrets protecting data | Met | quadrava + authz: no token or key in the browser (BFF) |
| 1.7.1 | Common logging format | Met | odt: the image logs one JSON object a line in the Elastic Common Schema (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) |
| 1.7.2 | Logs sent to a remote system | Install | The log system of the platform |
| 1.8.1 | Sensitive data identified and classified | Met | odt: [data.md](data.md), four levels and where each piece of data is |
| 1.8.2 | Protection requirements per level | Met | odt: [data.md](data.md), the protection of each level |
| 1.9.1 | Encrypted communications between components | Install | TLS on every link |
| 1.9.2 | Components verify each other's identity | Install | Trusted certificates, host name checks |
| 1.10.1 | Source control with change tracking | Met | odt: Git on GitHub, Conventional Commits, pull requests, protected `main` |
| 1.11.1 | Components and their functions documented | Met | odt: the modules of Spring Modulith, each with its `package-info.java`, and the ADRs |
| 1.11.2 | No unsynchronized shared state in sensitive flows | Met | authz: session in Redis; odt: optimistic versions (`If-Match`) and transactions |
| 1.12.2 | Uploaded files served as downloads | Met | odt: `Content-Disposition: attachment`, `nosniff` (ADR-14) |
| 1.14.1 | Segregation of components by trust | Install | Only the runtime published; the back end, the database and Redis on an inner network |
| 1.14.2 | Signed binaries and trusted deployment | Open | O4: the images are not signed |
| 1.14.3 | Build warns of insecure components | Met | odt: OSV-Scanner fails CI on high or critical; Dependabot |
| 1.14.4 | Build verifies a secure deployment | Met | odt: the e2e job deploys the stack and runs the kits, axe-core and ZAP |
| 1.14.5 | Deployments sandboxed and isolated | Install | Containers on separate networks |
| 1.14.6 | No deprecated client technologies | Met | quadrava: Angular, no plugins |

## V2 Authentication

OpenDevTrack has no login of its own: the users sign in to Keycloak through the BFF of authz-library.

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 2.1.1 | Passwords of at least 12 characters | Install | Keycloak password policy |
| 2.1.2 | 64 characters allowed, over 128 refused | Install | Keycloak password policy |
| 2.1.3 | No truncation | Met | kc |
| 2.1.4 | Any printable Unicode allowed | Met | kc |
| 2.1.5 | Users can change their password | Met | kc: account console |
| 2.1.6 | Change asks the current password | Met | kc |
| 2.1.7 | Checked against breached passwords | Install | Keycloak `passwordBlacklist` |
| 2.1.8 | Password strength meter | Open | O5: Keycloak's default theme has none |
| 2.1.9 | No composition rules | Install | Keycloak password policy |
| 2.1.10 | No periodic rotation | Install | Keycloak password policy |
| 2.1.11 | Paste and password managers allowed | Met | kc |
| 2.1.12 | Masked password can be shown | Met | kc: the eye button of the login form |
| 2.2.1 | Anti-automation against brute force | Met | authz: 20 logins per minute per IP; kc: brute force detection (Install) |
| 2.2.2 | Weak authenticators only as second factor | Met | kc: no SMS; email only for verification |
| 2.2.3 | Notification after credential changes | Install | Keycloak email event listener |
| 2.3.1 | Random initial passwords or codes | Met | kc: temporary passwords and action tokens |
| 2.3.2 | U2F or FIDO supported | Met | kc: WebAuthn |
| 2.3.3 | Renewal instructions for time-bound authenticators | N/A | No time-bound authenticators |
| 2.4.1 | Passwords stored with a KDF | Met | kc: Argon2 by default |
| 2.4.2 | Salt of at least 32 bits, unique | Met | kc |
| 2.4.3 | PBKDF2 iterations | Met | kc: Argon2, or PBKDF2 with 210,000 iterations if chosen |
| 2.4.4 | bcrypt work factor | N/A | bcrypt not used |
| 2.4.5 | Additional KDF iteration with a secret salt | Open | O6: Keycloak has no pepper |
| 2.5.1 | Recovery secret not sent in clear | Met | kc: a link with a signed action token |
| 2.5.2 | No hints or secret questions | Met | kc |
| 2.5.3 | Recovery does not reveal the password | Met | kc |
| 2.5.4 | No shared or default accounts | Install | The realm of an installation; the dev users exist only in the `dev` profile and `e2e/authz` |
| 2.5.5 | Notification on factor change | Install | Keycloak email event listener |
| 2.5.6 | Secure recovery mechanism | Met | kc: reset by mail link, OTP kept |
| 2.5.7 | Identity proofing when factors are lost | Install | Process of the administrators |
| 2.6.1 | Lookup secrets used once | Met | kc: recovery codes |
| 2.6.2 | Lookup secrets with enough entropy | Met | kc |
| 2.6.3 | Lookup secrets resistant to offline attacks | Met | kc |
| 2.7.1 | No clear-text out-of-band by default | Met | kc: no SMS |
| 2.7.2 | Out-of-band codes expire in 10 minutes | N/A | No out-of-band authenticator |
| 2.7.3 | Out-of-band codes used once | N/A | No out-of-band authenticator |
| 2.7.4 | Out-of-band over a secure channel | N/A | No out-of-band authenticator |
| 2.7.5 | Out-of-band code stored hashed | N/A | No out-of-band authenticator |
| 2.7.6 | Out-of-band code with 20 bits of entropy | N/A | No out-of-band authenticator |
| 2.8.1 | OTP with a defined lifetime | Met | kc: TOTP period |
| 2.8.2 | OTP keys protected | Met | kc |
| 2.8.3 | Approved algorithms for OTP | Met | kc: HMAC-SHA1/256 TOTP (RFC 6238) |
| 2.8.4 | OTP used once in its period | Met | kc |
| 2.8.5 | Reused OTP logged and notified | Open | O6: Keycloak refuses it but sends no notification |
| 2.8.6 | Physical OTP generator revocable | Met | kc: the administrator removes the credential |
| 2.8.7 | Biometrics only as second factor | N/A | No biometrics of the application (WebAuthn decides on the device) |
| 2.9.1 | Verification keys stored securely | Met | kc |
| 2.9.2 | Challenge nonce of 64 bits | Met | kc: WebAuthn |
| 2.9.3 | Approved algorithms for verification | Met | kc: WebAuthn |
| 2.10.1 | Service secrets not unchanging passwords | Install | Rotated by the platform; the tools use access tokens or personal tokens with an expiry (authz) |
| 2.10.2 | No default service credentials | Met | odt: `stack.sh` and `e2e/authz/run.sh` generate every credential into gitignored files |
| 2.10.3 | Service passwords protected at rest | Install | The secret store of the platform |
| 2.10.4 | Secrets not in the source | Met | odt: no secret in the repository; environment variables only; the CI pushes with tokens of GitHub |

## V3 Session management

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 3.1.1 | No session tokens in URLs | Met | authz: cookie only |
| 3.2.1 | New session token on authentication | Met | authz: Spring Security renews the session id at login |
| 3.2.2 | Session tokens with 64 bits of entropy | Met | authz: Spring Session ids (random UUID) |
| 3.2.3 | Session tokens only in secure cookies | Met | odt: `__Host-SESSION_ID` (`SESSION_ID` in the development profile, on http), `HttpOnly`, `Secure`, `SameSite=Lax` |
| 3.2.4 | Approved algorithms for tokens | Met | authz: `SecureRandom` |
| 3.3.1 | Logout and expiry invalidate the session | Met | authz: `/logout` removes the session from Redis and ends the Keycloak session |
| 3.3.2 | Re-authentication when active or idle | Met | authz: idle 30 min (`server.servlet.session.timeout`), absolute 10 h |
| 3.3.3 | Terminate other sessions after password change | Open | O7: the sessions of the BFF stay until they expire (authz-library request) |
| 3.3.4 | View and log out of active sessions | Open | O7: no list of a user's sessions (authz-library request) |
| 3.4.1 | `Secure` cookie | Met | odt: `server.servlet.session.cookie.secure: true` (false only in `dev`) |
| 3.4.2 | `HttpOnly` cookie | Met | odt: `http-only: true` |
| 3.4.3 | `SameSite` cookie | Met | odt: `same-site: lax`, with the CSRF token of authz |
| 3.4.4 | `__Host-` prefix | Met | odt: `__Host-SESSION_ID`, Secure, Path=/, no Domain (SessionCookieTest); the runtime passes it as it is |
| 3.4.5 | Cookie path when the domain is shared | Met | odt: no `Domain` attribute; path `/` on its own host |
| 3.5.1 | Users can revoke OAuth tokens | Met | authz: personal access tokens revoked by their owner; kc: account console for clients |
| 3.5.2 | Session tokens rather than static keys | Met | authz: sessions for people; access tokens or personal tokens with an expiry for tools |
| 3.5.3 | Stateless tokens signed and checked | Met | authz: Keycloak JWTs checked on their keys (resource server) |
| 3.7.1 | Valid session or re-authentication for sensitive actions | Open | O9: no step-up before administration or token creation |

## V4 Access control

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 4.1.1 | Enforced on a trusted service layer | Met | odt: the back end checks every request |
| 4.1.2 | Attributes for access control not set by users | Met | odt: the tenant comes from the session only (`CurrentUser`, ADR-09); roles from the database |
| 4.1.3 | Least privilege | Met | odt: project roles and permission schemes; the admin scopes of authz |
| 4.1.5 | Access control fails securely | Met | odt: refusal by default; an error ends with a Problem without data |
| 4.2.1 | Protection against IDOR | Met | odt: each query filters by the tenant and checks the project role; tests of other tenants (ProjectApiTest and the others) |
| 4.2.2 | Strong anti-CSRF | Met | authz: CSRF token (`X-XSRF-TOKEN`) and the `Origin` check on writes |
| 4.3.1 | MFA for administrative interfaces | Install | Keycloak OTP or WebAuthn required for the administrators |
| 4.3.2 | No directory browsing | Met | quadrava: no listing; odt: no static files |
| 4.3.3 | Step-up or segregation for high-value actions | Open | O9 |

## V5 Validation, sanitization and encoding

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 5.1.1 | HTTP parameter pollution | Met | odt: the data comes in JSON bodies; the few query parameters are typed filters and pages |
| 5.1.2 | Mass assignment | Met | odt: request records with only the editable fields; versions and owners set by the server |
| 5.1.3 | Positive validation of all input | Met | odt: lengths, patterns and allowed values in the controllers and the column constraints (keys, statuses, types) |
| 5.1.4 | Structured data typed and validated | Met | odt: typed records; custom fields checked against their definition (ADR-11) |
| 5.1.5 | Redirects to allow-listed destinations | Met | authz: the return page of the login must be one of `allowed-origins` |
| 5.2.1 | HTML from editors sanitized | Met | quadrava: restricted Markdown (`lc-markdown`), never HTML; odt stores text |
| 5.2.2 | Unstructured data sanitized (length, characters) | Met | odt: length limits on every text |
| 5.2.3 | No SMTP injection | Met | odt: Jakarta Mail through `MimeMessageHelper`; addresses from the directory, subjects encoded |
| 5.2.4 | No eval of user input | Met | odt: the guards of the workflows are CEL, a language without side effects, compiled and checked (ADR-10) |
| 5.2.5 | No template injection | Met | odt: the texts of the mails are filled by named values, not by evaluating user text |
| 5.2.6 | SSRF protection | Met | odt: webhook URLs must resolve to public addresses, checked when saved and when sent; no redirects (ADR-21) |
| 5.2.7 | SVG sanitized | Met | odt: attachments are downloads only, never shown inline |
| 5.2.8 | Markdown, CSS, XSL sandboxed | Met | quadrava: restricted Markdown, never HTML; no user CSS or XSL |
| 5.3.1 | Output encoding for the context | Met | quadrava: Angular binding; odt: JSON by Jackson |
| 5.3.2 | Encoding keeps Unicode | Met | odt: UTF-8 everywhere |
| 5.3.3 | Escaping against XSS | Met | quadrava: Angular binding, no `innerHTML` of user text |
| 5.3.4 | Parameterized queries | Met | odt: JPA and jOOQ with bound values; ODQL compiled to jOOQ, never to text (ADR-16) |
| 5.3.5 | Encoding where no parameters exist | Met | odt: the names in ODQL are mapped to known columns, not inserted |
| 5.3.6 | No JSON injection or eval | Met | odt: Jackson; quadrava: `JSON.parse` |
| 5.3.7 | No LDAP injection | N/A | No LDAP in the back end (Keycloak federation is of the installation) |
| 5.3.8 | No OS command injection | N/A | No operating system commands |
| 5.3.9 | No file inclusion | Met | odt: attachments stored under generated keys, never by their names (ADR-14) |
| 5.3.10 | No XPath or XML injection | N/A | No XML |
| 5.4.1 | Memory-safe strings | Met | Java |
| 5.4.2 | Constant format strings | Met | odt: SLF4J placeholders, `formatted` with constant formats |
| 5.4.3 | Integer overflow | Met | odt: sizes and counts in `long`, positions bounded |
| 5.5.1 | Serialized objects with integrity checks | Met | authz: the session in Redis is written and read by the server only |
| 5.5.2 | XML parsers restricted | N/A | No XML input |
| 5.5.3 | No deserialization of untrusted data | Met | odt: Jackson without default typing |
| 5.5.4 | `JSON.parse` in the browser | Met | quadrava |

## V6 Stored cryptography

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 6.1.1 | Regulated personal data encrypted at rest | Install | Encrypted volume; the names and mails are in Keycloak and in the texts of the issues |
| 6.1.2 | Health data encrypted at rest | N/A | No health data is collected |
| 6.1.3 | Financial data encrypted at rest | N/A | No financial data is collected |
| 6.2.1 | Crypto fails securely, no padding oracle | Met | odt: AES-GCM (authenticated); a wrong key or text is an error, never a partial result |
| 6.2.2 | Proven algorithms and libraries | Met | odt: Spring Security Crypto (`Encryptors.delux`), HMAC-SHA256 of the JDK |
| 6.2.3 | IV, cipher and modes configured securely | Met | odt: AES-256-GCM with a random IV per value |
| 6.2.4 | Algorithms can be changed | Met | odt: the stored form `enc:<id>:…` names the key; a new form can be added next to it |
| 6.2.5 | No insecure modes or ciphers | Met | odt: no ECB, DES or MD5 for security |
| 6.2.6 | Nonces not reused | Met | odt: random 96-bit IV per encryption |
| 6.3.1 | Random values from a CSPRNG | Met | odt: `SecureRandom` for the secrets of the webhooks; authz: for sessions and tokens |
| 6.3.2 | Random GUIDs are v4 from a CSPRNG | Met | odt: `UUID.randomUUID()` for event ids; the row ids (UUIDv7) are not secrets |
| 6.4.1 | Secrets management solution | Install | The secret store of the platform |
| 6.4.2 | Key material in an isolated module | Open | O10: `ODT_SECRET_KEY` is in the memory of the back end; a KMS would hold it outside |

## V7 Error handling and logging

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 7.1.1 | No credentials in logs | Met | odt: the webhook URLs logged as origin only, no secrets; authz: no tokens |
| 7.1.2 | No sensitive data in logs | Met | odt: user names and ids only |
| 7.1.3 | Security events logged | Met | authz: logins; kc: login events; odt: a line for each refusal of access control (`AccessRefusalLog`) |
| 7.1.4 | Log events allow a timeline | Met | odt: time, level, thread and logger in each line; the audit log of the administration (ADR-22) |
| 7.2.1 | Authentication decisions logged | Met | authz: session created, user not enabled; kc: login events (Install) |
| 7.2.2 | Access control decisions logged, failures always | Met | odt: every 401 and 403, whoever refuses, and the not-found of the API, with user and tenant (`AccessRefusalLog`) |
| 7.3.1 | Log encoding against injection | Met | odt: structured (JSON) logs escape line breaks and quotes of logged values |
| 7.3.3 | Security logs protected | Install | The log system of the platform; odt: the audit log is read-only through the API |
| 7.3.4 | Synchronized time | Install | NTP; odt: times stored in UTC (`timestamptz`) |
| 7.4.1 | Generic message on errors | Met | odt: Problems with a title and no internal details; Spring Boot hides stack traces and messages |
| 7.4.2 | Exception handling across the code | Met | odt: Problems from the controllers, `UploadLimit` |
| 7.4.3 | Last-resort error handler | Met | odt: Spring Boot's error handling, a Problem with status 500 and no details |

## V8 Data protection

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 8.1.1 | Sensitive data not cached by servers | Met | authz: `Cache-Control: no-store` on the answers of the API |
| 8.1.2 | Server temporary copies protected or purged | Met | odt: uploads streamed to the storage; no copies on disk beyond the request |
| 8.1.3 | Few parameters in requests | Met | odt: JSON bodies, no hidden state in the client |
| 8.1.4 | Detect abnormal request numbers | Open | O12: rate limits on the login and the bearer path only; no alert |
| 8.2.1 | Anti-caching headers | Met | authz: `Cache-Control: no-cache, no-store`, `Pragma`, `Expires` |
| 8.2.2 | No sensitive data in browser storage | Met | quadrava: only preferences (language, last project) in `localStorage` |
| 8.2.3 | Client data cleared at logout | Met | quadrava: the logout leaves the page (a redirect through the BFF); only the preferences stay |
| 8.3.1 | No sensitive data in query strings | Met | odt: the ODQL of a search goes in the body; ids only in paths |
| 8.3.2 | Users can remove or export their data | Open | O13: no export or removal of a user's data on request |
| 8.3.3 | Clear language and consent for personal data | Install | The privacy notice of the installation |
| 8.3.4 | Sensitive data identified, with a policy | Met | odt: [data.md](data.md), levels, protection and periods; reviewed with each ADR that adds data |
| 8.3.5 | Access to sensitive data audited | Met | odt: the audit log of the administration (ADR-22) and the history of the issues |
| 8.3.6 | Sensitive data overwritten in memory | N/A | The JVM gives no control over freed memory; secrets are held as short-lived strings |
| 8.3.7 | Encryption with confidentiality and integrity | Met | odt: AES-GCM for the webhook secrets |
| 8.3.8 | Retention of personal data | Met | odt: retention of messages, deliveries and the audit log (`odt.*.retention`); issues are kept by design |

## V9 Communication

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 9.1.1 | TLS for all client connections | Install | TLS at the proxy; odt: HSTS on the API answers |
| 9.1.2 | Strong cipher suites | Install | The proxy |
| 9.1.3 | Only TLS 1.2 and 1.3 | Install | The proxy |
| 9.2.1 | Trusted certificates | Install | The certificates of the platform |
| 9.2.2 | TLS for every inbound and outbound link | Install | PostgreSQL, Redis, Keycloak, SMTP and the storage over TLS; odt: webhooks may be `https` only when the URL says so |
| 9.2.3 | External encrypted links authenticated | Met | odt: webhooks signed with HMAC-SHA256 (ADR-21); the JDK checks the certificates |
| 9.2.4 | Certificate revocation checked | Install | OCSP stapling at the proxy |

## V10 Malicious code

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 10.2.1 | No unauthorized phone home or data collection | Met | odt: no telemetry; outbound only to the webhooks a tenant sets |
| 10.2.2 | No excessive device permissions | Met | quadrava: no camera, location or contacts |
| 10.3.1 | Signed updates over secure channels | N/A | No auto-update |
| 10.3.2 | Integrity protections (signing, SRI) | Met | quadrava: every script from its own image, no CDN |
| 10.3.3 | Subdomain takeover protection | Install | The DNS of the installation |

## V11 Business logic

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 11.1.1 | Flows in order | Met | odt: the transitions of the workflows are checked by the server (ADR-10) |
| 11.1.2 | Steps in realistic human time | N/A | No flow where speed is a risk (no purchases, no voting) |
| 11.1.3 | Limits per user on business actions | Met | odt: 100 attachments per issue, file size, page sizes |
| 11.1.4 | Anti-automation against excessive calls | Open | O12: the session API has no rate limit per user |
| 11.1.5 | Business logic limits from threat modeling | Met | odt: the limits in [threat-model.md](threat-model.md), «Limits of the business logic» |
| 11.1.6 | No TOCTOU on sensitive operations | Met | odt: optimistic versions on every edit, uniqueness by constraints, transactions |
| 11.1.7 | Monitor unusual business events | Open | O12 |
| 11.1.8 | Configurable alerting on attacks | Open | O12 |

## V12 Files and resources

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 12.1.1 | No large files | Met | odt: `odt.attachments.max-size` (25 MB) and the multipart limits |
| 12.1.2 | Compressed files checked before expanding | N/A | Files are never expanded |
| 12.1.3 | Quota of size and number per user | Open | O14: 100 files per issue, no quota per project or user (ADR-14) |
| 12.2.1 | File type checked by content | Open | O14: the type is the one declared, made safe; never shown inline |
| 12.3.1 | File names not used by the file system | Met | odt: storage keys are generated (ADR-14) |
| 12.3.2 | File names cannot reach local files | Met | odt: the name is only metadata |
| 12.3.3 | File names cannot reach remote files | Met | odt: the name is only metadata |
| 12.3.4 | No reflective file download | Met | odt: the download name is cleaned and quoted, `nosniff` |
| 12.3.5 | File metadata not passed to system commands | Met | odt: no commands |
| 12.3.6 | No code from untrusted sources | Met | odt: dependencies from Maven Central and GitHub Packages of the project, checked by OSV-Scanner |
| 12.4.1 | Uploaded files outside the web root | Met | odt: S3-compatible storage or a folder outside any web root (ADR-14) |
| 12.4.2 | Uploaded files scanned by an antivirus | Open | O14 |
| 12.5.1 | Only allowed file types served by the web tier | Met | quadrava: only the files of its build |
| 12.5.2 | Uploads never executed as HTML or JavaScript | Met | odt: `Content-Disposition: attachment`, `nosniff`, CSP `default-src 'none'` |
| 12.6.1 | Allow list for server requests | Met | odt: webhooks to public addresses only; the other outbound links are configured by the installation |

## V13 API and web service

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 13.1.1 | Same encodings and parsers everywhere | Met | odt: one Spring MVC stack, UTF-8 |
| 13.1.3 | No secrets in API URLs | Met | odt: tokens in headers only |
| 13.1.4 | Authorization at the URI and the resource | Met | odt: the security chain by path, then the project role on each resource |
| 13.1.5 | Unexpected content types refused | Met | odt: Spring MVC answers 415 to a body that is not JSON |
| 13.2.1 | HTTP methods allowed per user | Met | odt: each method of each resource checks its own permission |
| 13.2.2 | JSON schema validation | Open | O15: requests are checked by the controllers, not against a schema; the OpenAPI is published (`docs/openapi.json`) |
| 13.2.3 | CSRF protection on cookie APIs | Met | authz: CSRF token and `Origin` check |
| 13.2.5 | Content-Type checked | Met | odt: as 13.1.5 |
| 13.2.6 | Headers and payload not modified in transit | Met | odt: TLS (Install); webhook payloads signed |
| 13.3.1 | SOAP XSD validation | N/A | No SOAP |
| 13.3.2 | SOAP WS-Security | N/A | No SOAP |
| 13.4.1 | GraphQL depth and amount limits | N/A | No GraphQL; ODQL has page limits |
| 13.4.2 | GraphQL authorization in business logic | N/A | No GraphQL |

## V14 Configuration

| # | Summary | State | Where, or why |
| --- | --- | --- | --- |
| 14.1.1 | Secure, repeatable build and deploy | Met | odt: CI on GitHub Actions; images from Dockerfiles; `stack/compose.yaml` |
| 14.1.2 | Compiler hardening flags | N/A | Java bytecode; the JVM of the base image |
| 14.1.3 | Server configuration hardened | Met | odt: actuator only `health` and `info`; graceful shutdown; no error details |
| 14.1.4 | Automated re-deployment from a runbook | Met | odt: `stack/README.md`, `stack.sh` |
| 14.2.1 | Components up to date | Met | odt: Dependabot weekly; OSV-Scanner in CI |
| 14.2.2 | Unneeded features removed | Met | odt: no samples; the `dev` data only in the `dev` profile |
| 14.2.3 | SRI for external assets | N/A | No external assets: everything from the runtime image |
| 14.2.4 | Components from trusted repositories | Met | odt: Maven Central, GitHub Packages (authz-library, Quadrava), Docker official images |
| 14.2.5 | SBOM maintained | Met | odt: CycloneDX SBOM of the back end built in CI at each run (`dependencies` job) |
| 14.2.6 | Third-party libraries encapsulated | Met | odt: Spring Modulith modules; CEL, S3 and mail behind one class each |
| 14.3.2 | Debug modes off in production | Met | odt: no debug endpoints; `dev` profile only in development |
| 14.3.3 | No version details in headers | Met | odt: no `Server` or `X-Powered-By` from the back end |
| 14.4.1 | Content-Type with charset | Met | odt: `application/json` (UTF-8 by definition) and `application/problem+json` |
| 14.4.2 | `Content-Disposition` on API answers | Met | odt: `attachment` on every answer that sets none; attachments keep theirs, with the name of the file |
| 14.4.3 | Content Security Policy | Met | odt: `default-src 'none'` on the API answers; quadrava: its own files only, style elements with a nonce, images from the origin (0.15.0); style attributes stay allowed for NG-ZORRO |
| 14.4.4 | `nosniff` on all answers | Met | odt and quadrava |
| 14.4.5 | HSTS on all answers | Install | odt sends it on the API answers; the runtime sends it with `QUADRAVA_HSTS` |
| 14.4.6 | Referrer-Policy | Met | odt: `no-referrer`; quadrava: `same-origin` |
| 14.4.7 | No embedding by third-party sites | Met | odt: `frame-ancestors 'none'`; quadrava: `X-Frame-Options: SAMEORIGIN` |
| 14.5.1 | Only the HTTP methods in use | Met | odt: Spring MVC answers 405 to others |
| 14.5.2 | `Origin` not used for authentication | Met | authz: `Origin` checked on writes as an extra CSRF guard, never as identity |
| 14.5.3 | Strict CORS allow list | Met | authz: `allowed-origins`, no wildcard |
| 14.5.4 | Proxy headers authenticated | Met | odt: `X-Forwarded-*` only behind the runtime or a trusted proxy (`forward-headers-strategy`); tokens checked by the resource server |

## Open items

Each is an issue on GitHub, and a new one gets its own; 1.0 ships with none at high severity (ADR-23). Severity is our assessment for this
application.

| Item | Issue | Requirements | Severity | Work |
| --- | --- | --- | --- | --- |
| O4 | [#7](https://github.com/sramaccini/open-devtrack/issues/7) | 1.14.2 | Low | Signed images (cosign) and their check at deploy |
| O5 | [#8](https://github.com/sramaccini/open-devtrack/issues/8) | 2.1.8 | Low | A strength meter in a Keycloak theme of the project |
| O6 | [#9](https://github.com/sramaccini/open-devtrack/issues/9) | 2.4.5, 2.8.5 | Low | Keycloak features: pepper, notification of a reused OTP; to ask upstream or accept |
| O7 | [#10](https://github.com/sramaccini/open-devtrack/issues/10) | 3.3.3, 3.3.4 | Medium | authz-library: list and end a user's sessions, end them after a password change (back-channel logout of Keycloak) |
| O9 | [#12](https://github.com/sramaccini/open-devtrack/issues/12) | 3.7.1, 4.3.3 | Medium | Recent login (`max_age` or `acr`) before the administration and the creation of tokens |
| O10 | [#13](https://github.com/sramaccini/open-devtrack/issues/13) | 6.4.2 | Low | `ODT_SECRET_KEY` held by a KMS |
| O12 | [#15](https://github.com/sramaccini/open-devtrack/issues/15) | 8.1.4, 11.1.4, 11.1.7, 11.1.8 | Medium | A rate limit per user on the session API and alerts on unusual numbers |
| O13 | [#16](https://github.com/sramaccini/open-devtrack/issues/16) | 8.3.2 | Medium | Export and removal of a user's data on request |
| O14 | [#17](https://github.com/sramaccini/open-devtrack/issues/17) | 12.1.3, 12.2.1, 12.4.2 | Medium | Quotas per project, a type check by content, an antivirus hook for attachments |
| O15 | [#18](https://github.com/sramaccini/open-devtrack/issues/18) | 13.2.2 | Low | Requests checked against the OpenAPI schema |

Closed:

- O16 ([#19](https://github.com/sramaccini/open-devtrack/issues/19)): `Content-Disposition` on every answer.
- O17 ([#20](https://github.com/sramaccini/open-devtrack/issues/20)) and O18
  ([#21](https://github.com/sramaccini/open-devtrack/issues/21)): Quadrava 0.14.0 (requests Q18 and Q20).
- O19 ([#22](https://github.com/sramaccini/open-devtrack/issues/22)): Quadrava 0.15.0 (request Q21).
- O2 ([#5](https://github.com/sramaccini/open-devtrack/issues/5)): structured logs in the image.
- O8 ([#11](https://github.com/sramaccini/open-devtrack/issues/11)): the session cookie `__Host-SESSION_ID`.
- O11 ([#14](https://github.com/sramaccini/open-devtrack/issues/14)): a log line for each refusal of access control.
- O3 ([#6](https://github.com/sramaccini/open-devtrack/issues/6)): [data.md](data.md), the classification of the data.
- O1 ([#4](https://github.com/sramaccini/open-devtrack/issues/4)): [threat-model.md](threat-model.md).

Not open: row-level security of PostgreSQL, a second line under the checks of ADR-09 rather than a requirement of the
standard, left for 1.x with its measures (`docs/search-load.md`).
