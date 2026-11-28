# Configuration of the back end

The back end reads its settings from environment variables, which are the placeholders of
[`application.yml`](../../backend/src/main/resources/application.yml). Any other property of Spring Boot can be set
the same way, in capitals with `_` (`spring.mail.host` is `SPRING_MAIL_HOST`). In `deploy/compose`:

- the required variables are in `.env`;
- the optional ones are in `backend.env`, with only the lines that are set.

## Required

| Variable | What | Default |
| --- | --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL 18. The role owns its database and is neither a superuser nor `BYPASSRLS` | `jdbc:postgresql://localhost:5432/opendevtrack`, `opendevtrack`, none |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis 8.2: the sessions and the rate limits | `localhost`, `6379`, none |
| `IAM_ISSUER_URI` | The issuer of the realm, as the browser sees it: `https://login.example.org/realms/opendevtrack` | `http://localhost:8180/realms/opendevtrack` |
| `IAM_CLIENT_ID`, `IAM_CLIENT_SECRET` | The confidential client of the BFF | `opendevtrack-bff`, none |
| `FRONTEND_URL` | The public address of OpenDevTrack. It is the return page after login and logout, and the only origin allowed to write | `http://localhost:8090` |
| `ODT_SECRET_KEY` | The key of the secrets at rest, at least 32 characters (below). Not needed with `transit` | none: the back end does not start |

## Optional

| Variable | What | Default |
| --- | --- | --- |
| `PORT` | The port of the API and the BFF | `8080` |
| `MANAGEMENT_SERVER_PORT` | The port of `/actuator/health` and `/actuator/prometheus`, reachable only by Prometheus ([operations](../operations/README.md)) | the port of the API, where the metrics answer 401 |
| `ODT_PUBLIC_URL` | The address used in the links of the mails | `FRONTEND_URL` |
| `ODT_BEARER_CLIENTS` | The Keycloak clients whose access tokens the API accepts (comma separated): the publisher of `ui/` and the service accounts ([api.md](../api.md)) | `opendevtrack-tools` |
| `ODT_SESSION_RATE_LIMIT` | Requests a minute per user on `/api` and `/telemetry`, across their sessions (429 over it) | `600` |
| `ODT_RECENT_LOGIN_MAX_AGE` | How old a login may be before the sensitive actions (administration, workflows, schemes, groups, webhooks, tokens) ask for the password again | `15m` |
| `ODT_REPORTING_ZONE` | Time zone of the days of the reports and of the dates of search, and of the start of the work logged | `UTC` |
| `ODT_TIME_TRACKING_HOURS_PER_DAY`, `ODT_TIME_TRACKING_DAYS_PER_WEEK` | How long a day (`1d`) and a week (`1w`) of time tracking are, 1 to 24 hours and 1 to 7 days. Changing them changes how the minutes already logged are written, not the minutes | `8`, `5` |
| `ODT_TELEMETRY_OTLP_ENDPOINT` | The OpenTelemetry collector (OTLP/HTTP, for example `http://collector:4318`) that receives the traces and logs of the screens, redacted again by the back end | empty: accepted and dropped |
| `ODT_TELEMETRY_MAX_BYTES` | The largest body of a telemetry post (413 over it) | `1000000` |
| `ODT_AUDIT_RETENTION` | How long the audit log of the administration is kept | `P730D` |
| `ODT_WEBHOOKS_ALLOW_PRIVATE` | Webhooks also over http and to private addresses. For development only | `false` |
| `ODT_WEBHOOKS_ALLOWED_HOSTS` | Webhooks only to these host names, separated by commas, still at public addresses. The public demo allows only its echo | empty: every public host |
| `ODT_WEBHOOKS_DELAY` | How often due webhook deliveries are sent | `PT15S` |

### Mail

Mail is on only when `SPRING_MAIL_HOST` is set. Without it, the messages stay in the application.

| Variable | What | Default |
| --- | --- | --- |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | The SMTP server | none, `25` |
| `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | Its account | none |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE`, `…_REQUIRED` | TLS with STARTTLS (`true`, as asvs.md asks) | off |
| `ODT_MAIL_FROM` | The sender | `OpenDevTrack <noreply@localhost>` |
| `ODT_MAIL_DIGEST_AT` | The time of the daily digest, in `ODT_REPORTING_ZONE` | `07:00` |
| `ODT_MAIL_OUTBOX_DELAY` | How often the outbox is read | `PT1M` |

### Attachments

| Variable | What | Default |
| --- | --- | --- |
| `ODT_ATTACHMENTS_STORE` | `database`, `filesystem` or `s3` (ADR-14). The back end refuses to start if attachments are kept in another store: changing it is a migration | `database` |
| `ODT_ATTACHMENTS_FILESYSTEM_ROOT` | The folder of `filesystem`, on a volume of its own | none |
| `ODT_ATTACHMENTS_S3_BUCKET`, `…_PREFIX`, `…_ENDPOINT`, `…_REGION`, `…_PATH_STYLE`, `…_ACCESS_KEY`, `…_SECRET_KEY` | The bucket of `s3`. Without an endpoint, AWS S3; without keys, the AWS credential chain | none |
| `ODT_ATTACHMENTS_MAX_SIZE` | The largest file | `25MB` |
| `ODT_ATTACHMENTS_MAX_REQUEST_SIZE` | The largest upload request, a little more than the file | `26MB` |
| `ODT_ATTACHMENTS_PROJECT_QUOTA` | The bytes of all the attachments of a project, for example `5GB` (413 over it) | unlimited |
| `ODT_ATTACHMENTS_SCAN_CLAMD_HOST`, `…_PORT` | clamd before each file is stored. Its `StreamMaxLength` must be at least the largest file | off, `3310` |
| `ODT_ATTACHMENTS_SCAN_FAIL_OPEN` | Store a file unscanned when clamd does not answer, instead of a 503 | `false` |

### Secrets at rest

The secrets of the webhooks are encrypted in the database (ADR-23). Where their key lives:

- **`local`** (the default): the key is `ODT_SECRET_KEY`, in the memory of the back end. To rotate it, give the new
  key a new `ODT_SECRET_KEY_ID`, and put the old one in `ODT_SECRET_KEYS_PREVIOUS` as `id=key,id=key`. Keep it there
  until a start has written every secret again under the new key.
- **`transit`** (`ODT_SECRETS_PLUGIN=transit`, ADR-24): the key stays in the transit engine of Vault or OpenBao, and
  the back end never sees it.

  | Variable | Default |
  | --- | --- |
  | `ODT_SECRETS_TRANSIT_ADDRESS` | none |
  | `ODT_SECRETS_TRANSIT_MOUNT` | `transit` |
  | `ODT_SECRETS_TRANSIT_KEY` | `opendevtrack` |
  | `ODT_SECRETS_TRANSIT_TOKEN` or `ODT_SECRETS_TRANSIT_TOKEN_FILE` (read at each call) | none |

  The token needs `update` on `<mount>/encrypt/<key>` and `<mount>/decrypt/<key>`. Values written under a local key
  move to transit at the next start. Until then, `ODT_SECRET_KEY` is still needed.

### The registry of the screens

`ODT_UIREGISTRY_PROTECTED_ENVIRONMENTS` lists the environments where only versions approved by someone other than
their author are published. By default there are none (ADR-12): the review is the one of the code of `ui/`.
