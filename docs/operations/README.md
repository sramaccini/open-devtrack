# Operating OpenDevTrack

What an installation watches (ADR-24, ASVS 8.1.4, 11.1.7, 11.1.8). The rest of what an installation must do is in
[asvs.md](../security/asvs.md), «The installation».

## Metrics

The back end publishes its metrics for Prometheus at `/actuator/prometheus`, only on a management port of its own:
set `MANAGEMENT_SERVER_PORT` (for example `9090`), and let only Prometheus reach it. On the port of the API the path
answers 401, and the runtime does not forward `/actuator` at all. With a management port, `/actuator/health` moves
there too.

Besides the metrics of Spring Boot (HTTP, JVM, database pool), OpenDevTrack counts:

| Metric | Tags | What |
| --- | --- | --- |
| `odt_access_refused_total` | `status`: 401, 403, 404 (of `/api`), 429 | Each refusal, also written to the log with the user and the tenant (`AccessRefusalLog`) |
| `odt_logins_total` | `outcome`: `success`, `failure` | The OIDC logins of the BFF; a wrong password stays in Keycloak |
| `odt_webhook_attempts_total` | `outcome`: `delivered`, `failed` | Each attempt to deliver a webhook |
| `odt_attachments_refused_total` | `reason`: `quota`, `infected`, `scanner` | Uploads refused by the quota of the project or by the antivirus |
| `odt_attachments_unscanned_total` | | Files stored without a scan because clamd was not reachable and `fail-open` is set |

No metric has a tag per user, tenant or project: the number of series would grow with the installation. Who is behind
a number is in the logs, as structured fields (`user.name`, `odt.tenant`, `url.path`).

## Alerts

[alerts.yaml](alerts.yaml) holds Prometheus alert rules on these metrics, with thresholds for an installation of a few
hundred users. Load it in the `rule_files` of Prometheus, and set in Alertmanager who gets them. The thresholds can be
changed there without a release.

Two kinds of alert belong to the log system instead:

- **Per user**: for example more than 50 lines «Access refused» of one `user.name` in 5 minutes.
- **Keycloak**: wrong passwords, a reused OTP (refused by Keycloak, ADR-24 point 3), locked accounts. They are its
  `LOGIN_ERROR` events, or the metric `keycloak_user_events_total` when Keycloak publishes user event metrics.

## Verifying the image

A tag `vX.Y.Z` runs `.github/workflows/release.yml` (ADR-24 point 1, O4, ASVS 1.14.2): it publishes
`ghcr.io/sramaccini/opendevtrack-backend:X.Y.Z` (and `X.Y` and `latest`; a candidate `vX.Y.Z-rc.N` only its own tag,
[release.md](../release.md)), signs it without keys with cosign and the OIDC identity of
the workflow (Sigstore), attaches the CycloneDX SBOM as a signed attestation, and puts the same SBOM in the GitHub
release. Before deploying, an installation checks that the image comes from that workflow of this repository, for a
tag, and reads its SBOM:

```bash
cosign verify ghcr.io/sramaccini/opendevtrack-backend:X.Y.Z \
  --certificate-identity-regexp '^https://github\.com/sramaccini/open-devtrack/\.github/workflows/release\.yml@refs/tags/v[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

```bash
cosign verify-attestation --type cyclonedx ghcr.io/sramaccini/opendevtrack-backend:X.Y.Z \
  --certificate-identity-regexp '^https://github\.com/sramaccini/open-devtrack/\.github/workflows/release\.yml@refs/tags/v[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  | jq -r '.payload' | base64 -d | jq '.predicate.components | length'
```

Deploy by digest (`…@sha256:…`, printed by `cosign verify` and in the release notes), not by tag. A cluster can refuse
unsigned images by itself with a policy controller (Sigstore policy-controller, Kyverno `verifyImages`) set with the
same identity and issuer: that is the installation's. The Keycloak theme and the screens of `ui/` are not images: they
come from the archive `opendevtrack-<tag>.tar.gz` of the same release, whose SHA-256 is next to it.
