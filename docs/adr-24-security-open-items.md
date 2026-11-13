# ADR-24: The open security items that need a decision (ASVS level 2)

Status: Proposed (13 November 2026) — F5

## Context

ADR-23 listed the ASVS 4.0.3 level 2 requirements in `docs/security/asvs.md` and turned what was missing into open
items, each an issue. Most are closed:

- in OpenDevTrack: O1, O2, O3, O8, O11, O16;
- by Quadrava 0.14.0 and 0.15.0: O17, O18, O19.

O7 waits for authz-library (request A7). Nine are left, and each needs a choice before it is built: a new artifact
(images, a Keycloak theme), a new behaviour (a recent login, the removal of a user's data), an optional integration
(a key service, an antivirus), or accepting a gap.

| Item | Issue | Requirements | What is missing |
| --- | --- | --- | --- |
| O4 | #7 | 1.14.2 | The images are not signed; no release publishes them yet |
| O5 | #8 | 2.1.8 | No password strength meter: the login pages are Keycloak's default theme |
| O6 | #9 | 2.4.5, 2.8.5 | Keycloak has no pepper and does not notify a reused OTP |
| O9 | #12 | 3.7.1, 4.3.3 | No recent login is asked before the administration or the creation of a token |
| O10 | #13 | 6.4.2 | `ODT_SECRET_KEY` is in the memory of the back end |
| O12 | #15 | 8.1.4, 11.1.4, 11.1.7, 11.1.8 | No rate limit per user with a session; no alerts |
| O13 | #16 | 8.3.2 | A user's data cannot be exported or removed on request |
| O14 | #17 | 12.1.3, 12.2.1, 12.4.2 | Attachments: no quota per project, the type is the declared one, no antivirus |
| O15 | #18 | 13.2.2 | Requests are not checked against the published OpenAPI schema |

What is there to build on:

- the JSON Schema validator of the registry (`json-schema-validator`) is already a dependency;
- Spring Boot Actuator is there, without a metrics endpoint;
- authz-library counts requests on Redis for the login and the bearer path;
- the attachments go through one `AttachmentStore` with plugins (ADR-14), the secrets through `SecretBox` (ADR-23);
- a 401 of the BFF makes the Quadrava runtime show the session as expired, which leads to the login and back.

## Decision

1. **O4, signed images: with the release workflow.**
   - The release workflow of 1.0 (to write) publishes the back end image to `ghcr.io/sramaccini/opendevtrack-backend`.
   - It signs the image without keys, with cosign and the OIDC identity of the GitHub workflow (Sigstore), and
     attaches the SBOM (CycloneDX, already built) as an attestation.
   - `docs/` tells an installation how to check both with `cosign verify` and `cosign verify-attestation`, naming the
     identity of the workflow. A cluster can enforce it (a policy controller); that is the installation's.
   - 1.14.2 becomes Met for the image and Install for the check at deploy.
2. **O5, strength meter: a Keycloak theme of the project.**
   - `keycloak/themes/opendevtrack` extends the login theme of Keycloak with:
     - the logo and colours of `brand/`;
     - a strength meter on the pages that set a password (registration, update, reset).
   - The meter is a small script of the theme. It scores length and variety and flags the commonest passwords from a
     short list in the theme. It adds no library.
   - The policy that refuses a password stays Keycloak's (asvs.md, «The installation»): the meter only advises.
   - The realm of `e2e/authz` uses the theme, and the installation selects it.
3. **O6, pepper and reused OTP: accepted, not built.**
   - **No pepper (2.4.5).** Keycloak hashes with its own algorithm and iterations, and the passwords live in its
     database, separate from OpenDevTrack's. A pepper would need a Keycloak provider of our own to maintain against
     every Keycloak release, which is the heavy kind of solution AGENTS.md rules out.
   - **Reused OTP (2.8.5).** Keycloak refuses a reused OTP and records a failed login event. The installation's log
     system can alert on it, with the alerts of point 6.
   - Both are recorded in asvs.md with a new state, **Accepted**: not met, with the reason and the maintainer's
     decision.
   - Reconsidered if Keycloak adds a pepper or such a notification.
4. **O9, a recent login before sensitive actions: authz-library, then the back end.**
   - **The request (A8 to authz-library):**
     - the session keeps the `auth_time` of the login;
     - an application can mark paths that need a login younger than a period (`authz.bff.recent-login.paths`,
       `max-age`);
     - an older one gets a 401 with the problem type `recent-login-required`;
     - `/bff/login` accepts `max_age`, so Keycloak asks for the credentials again even within its own session.
   - **OpenDevTrack marks** the administration API (`/api/admin/**`, the admin API of authz-library, permission
     schemes, groups, workflows) and the creation of personal access tokens and webhooks, with 15 minutes.
   - **The runtime** already sends the user to the login on a 401 and back to the page. If it loses what the user was
     typing, that is a request to Quadrava.
5. **O10, the key outside the back end: an optional plugin, as the attachment stores.**
   - `SecretBox` gets its key operations from a `SecretKeys` interface, with two implementations:
     - `local`, the default and today's behaviour: the key from `ODT_SECRET_KEY`, in memory;
     - `transit`: encrypt and decrypt through the transit engine of Vault or OpenBao, over its HTTP API with a token
       from the platform. No SDK, and the key never leaves the service.
   - The stored values carry the plugin and key version, so values move from `local` to `transit` with a re-encryption
     job.
   - 6.4.2 becomes Install: met when the installation chooses `transit`.
   - A cloud KMS (AWS, Google, Azure) is left for a later plugin of the same interface, asked for by a user.
6. **O12, a rate limit per user and alerts.**
   - **A rate limit on the session API too**, the same Redis counter that authz-library already keeps for the bearer
     path. Request A9 to authz-library: `authz.bff.rate-limit` applied to signed-in sessions, 600 a minute per user by
     default, 429 with `Retry-After`.
   - **Metrics.** The back end publishes Micrometer counters, through `/actuator/prometheus`, on the management port
     only (installation):
     - refusals by status (from `AccessRefusalLog`);
     - 429s;
     - logins and failed logins (authz-library events);
     - webhook failures;
     - attachments refused (point 8).
     This adds `micrometer-registry-prometheus`.
   - **Alerts.** `docs/operations/alerts.yaml`: Prometheus alert rules with documented thresholds, for example a user
     with more than 50 refusals in 5 minutes, a burst of failed logins, 429s, a webhook failing for an hour. The
     installation loads them into its Prometheus or Alertmanager and sets where they go. Also for the Keycloak events
     of point 3.
7. **O13, export and removal of a user's data.**
   - **Export.**
     - `GET /api/me/export` gives the signed-in user a JSON of everything about them in the tenant: profile; the
       issues they reported, are assigned or review; their comments; their attachments' metadata; messages;
       preferences; saved filters; watches.
     - An administrator gets the same for a user of the tenant with `GET /api/admin/users/{username}/export`.
     - It is logged in the audit log.
   - **Removal**, by a tenant administrator, asked twice in the screen:
     - the user's name in issues, comments, history, links and audit becomes a pseudonym kept per tenant
       (`former-user-<n>`), so the work stays readable without the person;
     - their messages, preferences, filters, watches and mentions are deleted;
     - the user is removed from the tenant in authz-library.
     - The texts they wrote stay: they are project data, and a mention of them inside a text is the project's to edit.
       The screen says so.
     - The account in Keycloak is the installation's to delete, and the screen says so too.
   - `docs/security/data.md` lists what is exported and what is removed.
8. **O14, attachments.**
   - **Quota per project.** `odt.attachments.project-quota`, unlimited by default. Over it, an upload gets 413 with
     the quota in the problem.
   - **Type by content.** The first bytes of the file are compared with a small table of signatures in the code: PDF,
     PNG, JPEG, GIF, WebP, ZIP and the Office formats, gzip, plain text. No library.
     - A file whose content does not match its declared type is stored as `application/octet-stream`, not refused.
     - It is still always a download.
   - **Antivirus hook.** An optional `AttachmentScanner` with one implementation, clamd over TCP (`INSTREAM`), no
     dependency:
     - infected → 422, and the file is not stored;
     - clamd unreachable → refused (fail closed) unless `odt.attachments.scan.fail-open` is set;
     - off by default.
   - 12.1.3 and 12.2.1 become Met; 12.4.2 becomes Install (met when the installation runs clamd).
9. **O15, requests checked against the OpenAPI.**
   - A filter of the back end validates the JSON bodies of `/api` against the schemas of `docs/openapi.json`, the
     contract kept by `OpenApiContractTest`, with the validator already in the classpath.
   - A body that does not match gets 400 with the errors as the Problem contract of Quadrava has them (`errors[].path`).
   - The schemas are made strict where they are not (`additionalProperties: false` on the requests), in the same work.

The order of the work, each item one commit with its tests and closing its issue: O15, O14, O12 (metrics and
alerts), O10, O13, O5, O4 (with the release workflow). O9 and the rate limit of O12 follow A8 and A9. O6 is closed by
the update of asvs.md alone.

## Alternatives considered

- **O6: a Keycloak password hash provider with a pepper.** Possible with Keycloak's SPI, but a provider of our own to
  keep working at each Keycloak release, for a gain that the separation of Keycloak's database already gives in part.
- **O9: a confirmation dialog or the password typed again in OpenDevTrack.** OpenDevTrack never sees passwords; the
  identity provider must ask, and only a fresh login proves it.
- **O10: envelope encryption with a key fetched from the KMS at startup.** The key would still be in memory, which
  is what 6.4.2 asks to avoid.
- **O12: alerts inside OpenDevTrack (mails to administrators).** The installation already has, or should have, a
  monitoring system; thresholds in Prometheus rules can be changed without a release.
- **O13: deleting everything the user wrote.** It would break the history of the projects; pseudonymisation keeps
  the work and removes the person, the usual reading of the GDPR for shared work records.
- **O14: Apache Tika for the type.** A large dependency for a check that a table of signatures does for the types
  that matter; the rest stays a download as octet-stream.
- **O15: a dedicated request validator library.** The validator of the registry already reads JSON Schema; the
  contract file is already there.

## Consequences

- New: a release workflow with signed images; a Keycloak theme; metrics and alert rules; the `transit` key plugin; an
  export and a removal of a user's data; a quota, a check by content and an antivirus hook for attachments; the
  validation of requests.
- One new dependency: `micrometer-registry-prometheus`.
- Two requests to authz-library: A8, a recent login; A9, the rate limit on sessions.
- asvs.md gets the state Accepted, used for 2.4.5 and 2.8.5.
- When it is built, level 2 has no open requirement left; some are met by the installation, as listed in
  «The installation».
