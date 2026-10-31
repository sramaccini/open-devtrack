#!/usr/bin/env bash
# End-to-end check of the OpenDevTrack back end with authz-library (docs/check-authz-end-to-end.md).
#
#   e2e/authz/run.sh up      generate test credentials, start PostgreSQL, Redis and Keycloak, build and start the back
#                            end on the host (profile dev), then the Quadrava runtime in front of it
#   e2e/authz/run.sh check   sign in through the runtime and run the Quadrava conformance kits and the tenant checks
#   e2e/authz/run.sh down    stop the back end and remove the containers
#
# Needs Docker, curl, JDK 25 (JAVA_HOME), Maven with access to the authz-library packages (backend/README.md) and
# Node 24 in the PATH. Everything generated goes into e2e/authz/.work (gitignored). Development only.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
work="$here/.work"

# The conformance kits of Quadrava, downloaded file by file at a fixed commit. The authorization kit with session
# cookies is on Quadrava main after 0.1.0: pin the release tag once one carries it.
QUADRAVA_KITS="${QUADRAVA_KITS:-https://raw.githubusercontent.com/sramaccini/quadrava/9fa1916f4c584ec17734796cc67506d4e23444f6/frontend/tools}"

DB_PORT="${DB_PORT:-25432}"
REDIS_PORT="${REDIS_PORT:-26379}"
KEYCLOAK_PORT="${KEYCLOAK_PORT:-28180}"
APP_PORT="${APP_PORT:-28080}"
FRONT_PORT="${FRONT_PORT:-28090}"
export DB_PORT REDIS_PORT KEYCLOAK_PORT APP_PORT FRONT_PORT

front="http://localhost:$FRONT_PORT"
compose=(docker compose -f "$here/compose.yaml" --env-file "$work/.env")

log() { printf '\n== %s\n' "$*"; }

secret() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }

wait_for() {
  local url="$1" what="$2"
  for _ in $(seq 1 90); do
    if curl -fs -o /dev/null "$url"; then return 0; fi
    sleep 2
  done
  echo "$what did not answer at $url" >&2
  return 1
}

up() {
  mkdir -p "$work/kits"
  if [ ! -f "$work/.env" ]; then
    {
      echo "DB_PASSWORD=$(secret)"
      echo "KEYCLOAK_ADMIN_PASSWORD=$(secret)"
      echo "BFF_CLIENT_SECRET=$(secret)"
      echo "DEV_USER_PASSWORD=$(secret)"
    } > "$work/.env"
  fi

  log "Quadrava conformance kits"
  for kit in conformance-lib.mjs conformance-bff.mjs conformance-authz.mjs; do
    curl -fsS -o "$work/kits/$kit" "$QUADRAVA_KITS/$kit"
  done

  log "containers"
  "${compose[@]}" up -d --wait postgres redis
  "${compose[@]}" up -d keycloak
  wait_for "http://localhost:$KEYCLOAK_PORT/realms/opendevtrack/.well-known/openid-configuration" Keycloak

  log "back end (build)"
  mvn -q -B -f "$root/backend/pom.xml" package -DskipTests
  local jar
  jar="$(ls "$root"/backend/target/opendevtrack-backend-*.jar | grep -vE -- '-(sources|javadoc)\.jar$' | head -1)"

  log "back end (start, log in .work/backend.log)"
  set -a; . "$work/.env"; set +a
  DB_URL="jdbc:postgresql://localhost:$DB_PORT/opendevtrack" DB_USER=opendevtrack DB_PASSWORD="$DB_PASSWORD" \
  REDIS_PORT="$REDIS_PORT" IAM_CLIENT_SECRET="$BFF_CLIENT_SECRET" \
  IAM_ISSUER_URI="http://localhost:$KEYCLOAK_PORT/realms/opendevtrack" FRONTEND_URL="$front" PORT="$APP_PORT" \
    nohup "${JAVA_HOME:?set JAVA_HOME to a JDK 25}/bin/java" ${E2E_JAVA_OPTS:-} -jar "$jar" \
      --spring.profiles.active=dev > "$work/backend.log" 2>&1 &
  echo $! > "$work/backend.pid"
  wait_for "http://localhost:$APP_PORT/actuator/health" "The back end"

  # nginx resolves the BFF when it starts: the runtime comes last.
  "${compose[@]}" up -d runtime
  wait_for "$front/runtime-config.json" "The Quadrava runtime"
  log "ready: $front (users mario.rossi and anna.bianchi, password DEV_USER_PASSWORD in .work/.env)"
}

# Sign in through the runtime as a browser does; prints the cookies of the session for the kits.
login() {
  local user="$1" jar="$work/$1.cookies" page action
  rm -f "$jar"
  page="$(curl -fsSL -c "$jar" -b "$jar" "$front/bff/login?return=$front/")"
  action="$(printf '%s' "$page" | grep -o 'id="kc-form-login"[^>]*action="[^"]*"' | sed -e 's/.*action="//' -e 's/"$//' -e 's/&amp;/\&/g')"
  [ -n "$action" ] || { echo "no Keycloak login form for $user" >&2; return 1; }
  curl -fsSL -o /dev/null -c "$jar" -b "$jar" --data-urlencode "username=$user" \
    --data-urlencode "password=$DEV_USER_PASSWORD" "$action"
  # A first call of the page gives the XSRF-TOKEN cookie, as in the browser.
  curl -fs -o /dev/null -c "$jar" -b "$jar" "$front/bff/user"
  awk '$6 == "SESSION_ID" || $6 == "XSRF-TOKEN" { printf "%s%s=%s", sep, $6, $7; sep = "; " }' "$jar"
}

cookie_value() { awk -v n="$2" '$6 == n { print $7 }' "$work/$1.cookies"; }

user_json() { curl -fs -b "$work/$1.cookies" "$front/bff/user"; }

current_tenant() { user_json "$1" | grep -o '"currentTenant":"[^"]*"' | cut -d'"' -f4; }

check() {
  set -a; . "$work/.env"; set +a
  local kits="$work/kits" failed=0

  log "Quadrava kit: BFF contract, no session"
  node "$kits/conformance-bff.mjs" --bff "$front" --return "$front/" || failed=1

  log "Quadrava kit: authorization contract on the session (mario.rossi, other user anna.bianchi)"
  node "$kits/conformance-authz.mjs" --authz "$front" --app opendevtrack \
    --cookie "$(login mario.rossi)" --other-cookie "$(login anna.bianchi)" || failed=1

  log "Permissions of the catalogue of OpenDevTrack in the session (ADR-07)"
  local caps
  caps="$(curl -fs -b "$work/mario.rossi.cookies" "$front/authz/capabilities?app=opendevtrack")"
  echo "mario.rossi: $caps"
  case "$caps" in *'"odt.project.create"'*) echo "ok   odt.* permissions from the repeatable migration" ;;
    *) echo "FAIL odt.project.create missing"; failed=1 ;; esac

  log "Quadrava kit: BFF contract with a session of two tenants (anna.bianchi; the session ends with the logout)"
  node "$kits/conformance-bff.mjs" --bff "$front" --return "$front/" --cookie "$(login anna.bianchi)" || failed=1

  log "Tenants: the switch is kept for the next login (ADR-08)"
  login anna.bianchi > /dev/null
  local before target after xsrf
  before="$(current_tenant anna.bianchi)"
  target="$(user_json anna.bianchi | grep -o '"id":"[^"]*"' | cut -d'"' -f4 | grep -vx "$before" | head -1)"
  xsrf="$(cookie_value anna.bianchi XSRF-TOKEN)"
  curl -fs -o /dev/null -b "$work/anna.bianchi.cookies" -H "Origin: $front" -H "X-XSRF-TOKEN: $xsrf" \
    -H 'Content-Type: application/json' -d "{\"tenant\":\"$target\"}" "$front/bff/tenant"
  curl -fs -o /dev/null -b "$work/anna.bianchi.cookies" -H "Origin: $front" -H "X-XSRF-TOKEN: $xsrf" \
    -H 'Accept: application/json' -X POST "$front/logout"
  login anna.bianchi > /dev/null
  after="$(current_tenant anna.bianchi)"
  if [ -n "$target" ] && [ "$after" = "$target" ]; then echo "ok   $before → $target, kept after a new login"; else
    echo "FAIL $before → $target, after a new login: $after"; failed=1; fi

  [ "$failed" = 0 ] && log "all checks passed" || { log "some checks failed"; return 1; }
}

down() {
  if [ -f "$work/backend.pid" ]; then kill "$(cat "$work/backend.pid")" 2>/dev/null || true; rm -f "$work/backend.pid"; fi
  "${compose[@]}" down -v
}

case "${1:-}" in
  up) up ;;
  check) check ;;
  down) down ;;
  *) echo "usage: $0 up | check | down" >&2; exit 2 ;;
esac
