#!/usr/bin/env bash
# The watch of the public demo (deploy/demo/README.md, «Alerts»): run by cron every 5 minutes on its host, it checks
# the demo and reports to an external monitor, which mails the maintainer when a report says something is wrong or
# when no report comes at all (the host is down).
#
#   deploy/demo/watch.sh    the checks; their result goes to WATCH_PING_URL, or only to the output when it is not set
#
# WATCH_PING_URL is the ping address of a check of the monitor (healthchecks.io or one compatible with it: the address
# for a success, the same with /fail for a failure, the reasons in the body). It goes into the same env file as the
# other settings.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
env_file="${ODT_ENV_FILE:-$root/deploy/compose/.env}"
set -a; . "$env_file"; set +a

problems=()
problem() { problems+=("$*"); }

# The pages, as a visitor reaches them: the front, the back end through the BFF (401 without a session), Keycloak
code() { curl -s -o /dev/null -m 20 -w '%{http_code}' "$@"; }
[ "$(code "$ODT_URL/")" = 200 ] || problem "the front does not answer 200"
[ "$(code "$ODT_URL/bff/user")" = 401 ] || problem "the back end does not answer through the BFF"
[ "$(code "$KEYCLOAK_URL/realms/${KEYCLOAK_REALM:-opendevtrack}/.well-known/openid-configuration")" = 200 ] ||
  problem "Keycloak does not answer"
[ "$(code -X POST -d '{}' "https://$ECHO_HOST/watch")" = 200 ] || problem "the echo of the webhooks does not answer"

# The containers of the project: every one running, none unhealthy
while read -r name state health; do
  [ "$state" = running ] || problem "container $name is $state"
  [ "$health" = unhealthy ] && problem "container $name is unhealthy"
done < <(docker ps -a --filter "label=com.docker.compose.project=$ODT_PROJECT" \
  --format '{{.Names}} {{.State}} {{if .Health}}{{.Health}}{{else}}-{{end}}' 2>/dev/null ||
  docker ps -a --filter "label=com.docker.compose.project=$ODT_PROJECT" --format '{{.Names}} {{.State}} -')

# The certificates: Caddy renews them 30 days before they expire, so 14 days left means the renewal fails
for host in "$ODT_HOST" "$KEYCLOAK_HOST" "$ECHO_HOST"; do
  echo | openssl s_client -connect "$host:${HTTPS_PORT:-443}" -servername "$host" 2> /dev/null |
    openssl x509 -noout -checkend $((14 * 86400)) > /dev/null 2>&1 || problem "the certificate of $host expires within 14 days"
done

# The disk of the host and the memory available
used="$(df --output=pcent / | tail -1 | tr -dc 0-9)"
[ "$used" -lt 85 ] || problem "the disk is $used% full"
avail="$(awk '/MemAvailable/ { print int($2 / 1024) }' /proc/meminfo)"
[ "$avail" -gt 300 ] || problem "only $avail MB of memory available"

if [ ${#problems[@]} -eq 0 ]; then
  report="ok"
  suffix=""
else
  report="$(printf '%s\n' "${problems[@]}")"
  suffix="/fail"
fi
printf '%s %s\n' "$(date -u +%FT%TZ)" "${report//$'\n'/; }"
if [ -n "${WATCH_PING_URL:-}" ]; then
  curl -fsS -m 10 --retry 3 -o /dev/null --data-raw "$report" "$WATCH_PING_URL$suffix" ||
    echo "the monitor did not take the report" >&2
fi
[ -z "$suffix" ]
