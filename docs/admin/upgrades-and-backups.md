# Backups and upgrades

## What to back up

| What | How | Without it |
| --- | --- | --- |
| The database `opendevtrack` | `pg_dump -Fc`, every night and before each upgrade | Everything: projects, issues, users and roles (schema `authz`), the screens in the registry |
| The database `keycloak` | `pg_dump -Fc`, with the other one | The accounts, passwords and OTP of the users |
| The attachments outside the database | The folder of `filesystem`, or the versioning of the bucket of `s3` | The files. With the store `database` (the default) they are in the dump |
| `ODT_SECRET_KEY`, or the key in Vault transit | In the secret store, apart from the dumps | The secrets of the webhooks, which then have to be set again |
| `.env` and `backend.env` | With the secrets of the installation | The settings |

Redis holds only the sessions and the rate limits. Losing it signs everyone out, and nothing else.

With `deploy/compose`:

```bash
./odt.sh status
docker compose exec -T postgres pg_dump -U postgres -Fc opendevtrack > opendevtrack-$(date +%F).dump
docker compose exec -T postgres pg_dump -U postgres -Fc keycloak > keycloak-$(date +%F).dump
```

A dump holds personal data: keep it encrypted, and for no longer than the period of the installation
([data.md](../security/data.md)).

## Restoring

1. Stop the back end: `docker compose stop backend`.
2. Restore the dump into an empty database owned by the same role:
   `pg_restore -U postgres -d opendevtrack --no-owner --role=opendevtrack`.
3. Start the back end again with the same image, or a newer one, and the same secret key. The read models of search
   and reports are tables of the same database, so they come back with the dump.

## Upgrades

A release says in its notes and in `CHANGELOG.md` what changes. Within 1.x, an upgrade keeps the data and the public
API ([api.md](../api.md)). For each upgrade:

1. Read the notes, and verify the new image ([operations](../operations/README.md), «Verifying the image»).
2. Back up both databases.
3. Take the files of the release (`deploy/`, `ui/`, `keycloak/`, `brand/`), and set the new `ODT_BACKEND_IMAGE` by
   digest in `.env`.
4. `./odt.sh up`: the back end applies its new migrations when it starts (Flyway), before it answers.
5. `./odt.sh publish-ui`: the screens of the new release, which may use API that only the new back end has.

A migration cannot be undone. To go back, restore the dumps of step 2 and start the previous image. Upgrade one
release at a time, through each minor version in turn: 1.2 to 1.3 to 1.4, not 1.2 to 1.4.
