# Administrator guide

For whoever installs and runs OpenDevTrack (ADR-25). How it is built and why is in the [ADRs](../README.md); what
the users do is in the [user guide](../guide/README.md).

| Page | What |
| --- | --- |
| [installation.md](installation.md) | The parts, a first installation with `deploy/compose`, the first administrator, the screens |
| [configuration.md](configuration.md) | Every setting of the back end, with its default |
| [keycloak.md](keycloak.md) | The realm: users, password policy, OTP, mails, events, the login theme |
| [administration.md](administration.md) | The Administration area: users and roles, tenants, groups, permission schemes, logs |
| [upgrades-and-backups.md](upgrades-and-backups.md) | Backups, restores and upgrades |
| [../operations/README.md](../operations/README.md) | Metrics, alerts, verifying the image |
| [../security/asvs.md](../security/asvs.md), «The installation» | What an installation must do for security |

`stack/` is not an installation: it runs on plain HTTP with the development users, to try the tracker
([stack/README.md](../../stack/README.md)).
