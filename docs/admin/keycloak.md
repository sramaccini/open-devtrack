# Keycloak

OpenDevTrack signs its users in with Keycloak 26 and keeps no password. The template of the realm,
[deploy/keycloak/realm-opendevtrack.json](../../deploy/keycloak/realm-opendevtrack.json), has no users and sets what
[asvs.md](../security/asvs.md) asks of the realm («The installation»). Keycloak imports it on the first start of the
profile `keycloak` of `deploy/compose`. An existing Keycloak imports it with *Create realm → Browse*, after its
placeholders are replaced:

- `${ODT_URL}`: the address of OpenDevTrack;
- `${IAM_CLIENT_SECRET}` and `${PUBLISHER_CLIENT_SECRET}`: the secrets of the two clients;
- `${BFF_BACKCHANNEL_URL}`: `<back end>/bff/backchannel-logout`, as Keycloak reaches the back end.

## What the template sets

- **Clients**:
  - `opendevtrack-bff` (confidential, code flow with PKCE): the login of the browser. Its redirect URI is
    `<ODT_URL>/login/oauth2/code/iam`. It has a back-channel logout with the session required and no front-channel
    logout, so a session ended in Keycloak ends in OpenDevTrack too.
  - `opendevtrack-publisher`: a service account that publishes the screens (`./odt.sh publish-ui`).
  - Further service accounts for integrations are added as [api.md](../api.md) says, and listed in
    `ODT_BEARER_CLIENTS`.
- **Passwords**: at least 12 characters and at most 128, not the username, and not in the list of breached passwords.
  There are no composition rules and no expiry.
- **Brute force detection**: after 10 failures the account waits, a minute more each time, up to 15 minutes.
- **No self-registration**: users are added by the administrators. The login is by username only.
- **Events**: login and admin events are saved for 90 days and written to the log (`jboss-logging`). The listener
  `email` mails the user on a change of password or OTP.
- **Login theme**: `opendevtrack`, from [keycloak/](../../keycloak/README.md) with the logo and colours of `brand/`,
  and a strength meter on the password pages.

## What the installation adds

- **The list of breached passwords**: a text file with one password a line, in lower case, mounted as
  `/opt/keycloak/data/password-blacklists/breached-passwords.txt`. For example, use the most common passwords of a
  published breach corpus. Keycloak does not start without it, since the policy names it.
- **Mail** (*Realm settings → Email*): an SMTP server with TLS, used for the reset of a password and the notices on a
  change of credentials. Without it, *Forgot password* does not work.
- **OTP for the administrators**: give *Configure OTP* to every user with an administrative role (`GLOBAL_ADMIN`,
  `TENANT_ADMIN`, `ODT_ADMIN`; [administration.md](administration.md)). Once configured, Keycloak asks for it at each
  login. A realm that wants OTP for everyone makes *OTP Form* required in its browser flow.
- **The events to the log system**: the log of the Keycloak container goes to the central log system, where the
  alerts on `LOGIN_ERROR` are set ([operations](../operations/README.md), «Alerts»).
- **The temporary administrator** of the master realm (`KEYCLOAK_ADMIN`): after the first login, create a permanent
  administrator with OTP and delete the temporary one.
- **The admin console**: limit `/admin/*` of Keycloak to the addresses of its administrators at the proxy.

## Users

A person needs two things:

- an account in Keycloak, in the realm `opendevtrack`, with the required action *Update Password*;
- a user of OpenDevTrack with roles in a tenant (*Administration → Users → Add user*), with the same username.

A Keycloak account that has no roles in OpenDevTrack is refused after the password: the BFF of authz-library sends
it to the login error page with `access_denied`. Removing a person is done in both places:

- in OpenDevTrack, the removal of their data, which replaces the name with a pseudonym ([administration.md](administration.md));
- in Keycloak, the deletion of the account.
