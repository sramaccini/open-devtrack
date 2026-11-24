# The public demo

A public instance of OpenDevTrack for trying it without installing anything (ADR-25 point 3), at
https://demo.ods.logicwarez.com. **Everything in it is
erased every night: enter nothing private.** The accounts are shared, so anything written there is seen by every
visitor.

## Accounts

| Username | Password | What they can do |
|---|---|---|
| `mario.rossi` | `odt-demo-mario` | Admin of the sample projects (he created them): their members, workflows, boards, sprints and webhooks |
| `anna.bianchi` | `odt-demo-anna` | Member of the tenant: developer in *Website relaunch*, admin of *Mobile app* |
| `giulia.verdi` | `odt-demo-giulia` | Admin of the tenant: every project, and the users and roles of the tenant |

The passwords are public on purpose and cannot be changed: the account page of Keycloak is off, nobody is asked to
set a new password or an OTP, and failed logins do not lock an account. Every night, at the time of the reset:

- the database goes back to a snapshot with only the users and the screens, and the sample projects of `stack/`
  (*Website relaunch* and *Mobile app*) are added again, with dates relative to that day;
- every session is closed.

What does not leave the demo:

- **Mail** goes to a mail catcher of the demo, whose page is not published.
- **Webhooks** go only to the receiver of the demo, `https://<echo host>/<any path>`. Every other address is refused
  (`ODT_WEBHOOKS_ALLOWED_HOSTS`).
- **Attachments** have a quota of 10 MB per project.

## Running it

For the maintainer. The demo is [`deploy/compose`](../compose/) as an installation runs it
([docs/admin/installation.md](../../docs/admin/installation.md)), with the file [`compose.yaml`](compose.yaml) of this
folder on top. That file adds:

- Mailpit;
- the echo of the webhooks behind Caddy, which takes only `POST` ([`Caddyfile`](Caddyfile));
- the demo settings of the back end.

The global administrator, the registry author and Vault are not used.

What the host needs:

- a small VPS: 2 vCPU, 4 GB and 40 GB of disk, with Docker and the Compose plugin;
- ports 80 and 443 open to the internet, for Let's Encrypt;
- three DNS names on its address: OpenDevTrack (`ODT_HOST`), Keycloak (`KEYCLOAK_HOST`) and the echo (`ECHO_HOST`);
- a clone of this repository at the tag of the release, for the compose files, the realm, the theme and `ui/`.

1. `deploy/compose/.env` as the guide says, with:
   - `COMPOSE_PROFILES=proxy,keycloak`;
   - the image of the release by digest;
   - its secrets generated on the host;
   - `ECHO_HOST=` the third name.

   The secret key, `ODT_SECRET_KEY`, comes from this file and never leaves the host. The list of breached passwords
   goes into `deploy/compose/password-blacklists/breached-passwords.txt`, as the guide says: Keycloak does not start
   without it. The demo uses the 100,000 passwords of `100k-most-used-passwords-NCSC.txt` of SecLists, in lower case.
2. `deploy/demo/demo.sh setup`, once. It starts the containers, then:
   - sets the realm for the demo, as above;
   - creates the three users in Keycloak and the tenant `DEMO` with their roles;
   - publishes `ui/`;
   - takes the base snapshot into `deploy/demo/.work/`;
   - adds the sample projects.
3. The ZAP baseline against the demo before it opens (`e2e/zap`), and the alerts (below).
4. The nightly reset and the watch, in the crontab of the user that runs Docker:

   ```
   30 3 * * * /opt/open-devtrack/deploy/demo/demo.sh reset >> /var/log/odt-demo.log 2>&1
   */5 * * * * /opt/open-devtrack/deploy/demo/watch.sh >> /var/log/odt-demo-watch.log 2>&1
   ```

5. At each release:
   - check out the new tag;
   - put the new digest in `.env`;
   - run `deploy/demo/demo.sh upgrade`. It resets to the base snapshot, upgrades it, publishes `ui/` again, takes a
     new base snapshot and adds the sample projects.

`demo.sh hooks` shows what the echo received.

## Alerts

The demo has no Prometheus: the metrics and rules of [docs/operations](../../docs/operations/README.md) are for an
installation with real users. Its alerts come from an external monitor that mails the maintainer, so they also come
when the host itself is down. It is [healthchecks.io](https://healthchecks.io), or a service compatible with it, with
two checks whose ping addresses go into `.env`:

- `WATCH_PING_URL`: a check with a period of 5 minutes, for [`watch.sh`](watch.sh). Every 5 minutes it checks the
  front, the back end through the BFF, Keycloak and the echo, the containers, the certificates (14 days left), the
  disk (85%) and the memory. It reports a success, or a failure with its reasons, and no report at all means the host
  is down.
- `RESET_PING_URL`: a check with the schedule of the reset (`30 3 * * *`, UTC), for `demo.sh reset`, which reports
  its exit code when it ends.
