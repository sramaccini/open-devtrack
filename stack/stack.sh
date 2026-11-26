#!/usr/bin/env bash
# OpenDevTrack in Docker, for trying it and reviewing it (stack/README.md).
#
#   stack/stack.sh up       generate the credentials (first time), build the back end image, start everything,
#                           publish ui/ to the registry and add the sample projects (first time)
#   stack/stack.sh publish  publish ui/ again, after a change of the screens
#   stack/stack.sh seed     add the sample projects, when they are not there
#   stack/stack.sh info     the addresses and the users
#   stack/stack.sh logs     follow the log of the back end
#   stack/stack.sh screenshots  the images of the user guide, into docs/guide/images (needs Node 24 on the host)
#   stack/stack.sh down     stop the containers, keeping the data
#   stack/stack.sh reset    stop the containers and delete the data (the credentials are kept)
#
# Needs Docker with Compose, bash and curl, and a GitHub token with read:packages for authz-library and the Quadrava
# CLI: PACKAGES_TOKEN, or the one of npm.pkg.github.com in ~/.npmrc. Everything generated goes into stack/.work
# (gitignored). Development only.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
work="$here/.work"

KEYCLOAK_PORT="${KEYCLOAK_PORT:-18180}"
APP_PORT="${APP_PORT:-18080}"
FRONT_PORT="${FRONT_PORT:-18090}"
MAILPIT_PORT="${MAILPIT_PORT:-18025}"
DB_PORT="${DB_PORT:-15432}"
export KEYCLOAK_PORT APP_PORT FRONT_PORT MAILPIT_PORT DB_PORT

front="http://localhost:$FRONT_PORT"
compose=(docker compose -f "$here/compose.yaml" --env-file "$work/.env")

log() { printf '\n== %s\n' "$*"; }

# The sample projects (login, api, seed), shared with the public demo
. "$here/samples.sh"

secret() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

wait_for() {
  local url="$1" what="$2"
  for _ in $(seq 1 150); do
    if curl -fs -o /dev/null "$url"; then return 0; fi
    sleep 2
  done
  echo "$what did not answer at $url" >&2
  return 1
}

credentials() {
  mkdir -p "$work"
  if [ ! -f "$work/.env" ]; then
    {
      echo "DB_PASSWORD=$(secret)"
      echo "KEYCLOAK_ADMIN_PASSWORD=$(secret)"
      echo "BFF_CLIENT_SECRET=$(secret)"
      echo "DEV_USER_PASSWORD=$(secret)"
    } > "$work/.env"
  fi
  # The secret of the service account client opendevtrack-ci (ADR-21), also in an .env written before it existed
  grep -q '^CI_CLIENT_SECRET=' "$work/.env" || echo "CI_CLIENT_SECRET=$(secret)" >> "$work/.env"
  # The key of the secrets at rest (ADR-23), also in an .env written before it existed
  grep -q '^ODT_SECRET_KEY=' "$work/.env" || echo "ODT_SECRET_KEY=$(secret)" >> "$work/.env"
  set -a; . "$work/.env"; set +a
  SAMPLES_PASSWORD="$DEV_USER_PASSWORD"
}

# The GitHub token for the packages: PACKAGES_TOKEN, else the one npm already uses for npm.pkg.github.com.
packages() {
  if [ -z "${PACKAGES_TOKEN:-}" ] && [ -f "$HOME/.npmrc" ]; then
    PACKAGES_TOKEN="$(sed -n 's#^//npm.pkg.github.com/:_authToken=##p' "$HOME/.npmrc" | head -1)"
  fi
  [ -n "${PACKAGES_TOKEN:-}" ] || { echo "set PACKAGES_TOKEN to a GitHub token with read:packages" >&2; exit 1; }
  export PACKAGES_TOKEN
  # Written for the build and the CLI only, in the gitignored .work: the Maven server of backend/README.md and the
  # npm scope of the root .npmrc.
  umask 077
  cat > "$work/maven-settings.xml" <<EOF
<settings>
  <servers>
    <server>
      <id>authz-library</id>
      <username>token</username>
      <password>$PACKAGES_TOKEN</password>
    </server>
  </servers>
</settings>
EOF
  printf '%s\n' '@sramaccini:registry=https://npm.pkg.github.com' \
    '//npm.pkg.github.com/:_authToken=${NODE_AUTH_TOKEN}' > "$work/npmrc"
  umask 022
}

# Access token of a user for the tools client (password grant, development only): the registry takes bearer tokens.
token() {
  curl -fs -d grant_type=password -d client_id=opendevtrack-tools -d scope=openid \
    --data-urlencode "username=$1" --data-urlencode "password=$DEV_USER_PASSWORD" \
    "http://localhost:$KEYCLOAK_PORT/realms/opendevtrack/protocol/openid-connect/token" \
    | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4
}

publish() {
  log "ui/ published to the registry (environment development, author mario.rossi)"
  QUADRAVA_TOKEN="$(token mario.rossi)" "${compose[@]}" run --rm publish
}

info() {
  log "OpenDevTrack: $front"
  echo "users mario.rossi and anna.bianchi, password: $DEV_USER_PASSWORD"
  echo "API:      http://localhost:$APP_PORT/api (docs/api.md); service account opendevtrack-ci, secret CI_CLIENT_SECRET in stack/.work/.env"
  echo "mails:    http://localhost:$MAILPIT_PORT"
  echo "database: localhost:$DB_PORT, database and user opendevtrack, password DB_PASSWORD in stack/.work/.env"
  echo "webhooks: http://echo:8080/anything as the address, then stack.sh hooks to see what it got"
  echo "Keycloak: http://localhost:$KEYCLOAK_PORT (admin, password KEYCLOAK_ADMIN_PASSWORD in stack/.work/.env)"
}

# The images of the user guide (docs/guide/README.md), signed in as mario.rossi on the sample projects
screenshots() {
  log "screenshots of the user guide into docs/guide/images (e2e/a11y/screenshots.mjs)"
  (cd "$here/../e2e/a11y" && npm ci --silent --no-audit --no-fund --registry=https://registry.npmjs.org \
    && node screenshots.mjs --front "$front" --user mario.rossi --password "$DEV_USER_PASSWORD" \
      --out "$here/../docs/guide/images" ${A11Y_CHANNEL:+--channel "$A11Y_CHANNEL"})
}

up() {
  credentials
  packages
  log "back end image (built from backend/) and containers"
  "${compose[@]}" up -d --wait postgres redis mailpit echo keycloak
  "${compose[@]}" up -d --build backend
  wait_for "http://localhost:$APP_PORT/actuator/health" "The back end"
  publish
  "${compose[@]}" up -d runtime
  wait_for "$front/runtime-config.json" "The Quadrava runtime"
  seed
  info
}

case "${1:-}" in
  up) up ;;
  publish) credentials; packages; publish ;;
  seed) credentials; seed ;;
  info) credentials; info ;;
  logs) credentials; "${compose[@]}" logs -f backend ;;
  hooks) credentials; "${compose[@]}" logs -f echo ;;
  screenshots) credentials; screenshots ;;
  down) credentials; "${compose[@]}" down ;;
  reset) credentials; "${compose[@]}" down -v ;;
  *) echo "usage: $0 up | publish | seed | info | logs | hooks | screenshots | down | reset" >&2; exit 2 ;;
esac
