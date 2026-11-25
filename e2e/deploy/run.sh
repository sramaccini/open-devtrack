#!/usr/bin/env bash
# Check of the installation of deploy/compose (ADR-25 point 2): the production Compose file with its profiles proxy
# and keycloak, an image of the back end built from backend/, the realm template, the first administrator and the
# screens of ui/ published as the guide says (docs/admin/installation.md); then a login over https and a few calls.
#
#   e2e/deploy/run.sh up      credentials into .work, the image, the containers, a user in Keycloak, first-admin, publish-ui
#   e2e/deploy/run.sh check   login through Caddy and Keycloak, headers, permissions, a project
#   e2e/deploy/run.sh down    remove the containers and their volumes
#
# Needs Docker with Compose, bash, curl and a GitHub token with read:packages (PACKAGES_TOKEN, or the one of
# npm.pkg.github.com in ~/.npmrc). DEPLOY_IMAGE skips the build and uses that image; BACKEND_JAR builds the image
# from that jar instead of running Maven in Docker. Development only: the
# certificates are those of the local authority of Caddy, so curl runs with -k.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
work="$here/.work"
odt="$root/deploy/compose/odt.sh"

HTTPS_PORT="${HTTPS_PORT:-28443}"
KEYCLOAK_TEST_PORT="${KEYCLOAK_TEST_PORT:-28181}"
export KEYCLOAK_TEST_PORT
front="https://localhost:$HTTPS_PORT"
admin=ada.lovelace

export ODT_ENV_FILE="$work/.env"
export ODT_COMPOSE_FILES="$here/compose.yaml"

log() { printf '\n== %s\n' "$*"; }
secret() { head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; }
# A path Docker understands, also from Git Bash on Windows
host_path() { if command -v cygpath > /dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

packages() {
  if [ -z "${PACKAGES_TOKEN:-}" ] && [ -f "$HOME/.npmrc" ]; then
    PACKAGES_TOKEN="$(sed -n 's#^//npm.pkg.github.com/:_authToken=##p' "$HOME/.npmrc" | head -1)"
  fi
  [ -n "${PACKAGES_TOKEN:-}" ] || { echo "set PACKAGES_TOKEN to a GitHub token with read:packages" >&2; exit 1; }
}

credentials() {
  mkdir -p "$work/password-blacklists"
  # A short list for the check; an installation puts a real one here (docs/admin/keycloak.md)
  printf '%s\n' password123456 qwertyuiop123 opendevtrack123 > "$work/password-blacklists/breached-passwords.txt"
  if [ ! -f "$work/.env" ]; then
    umask 077
    cat > "$work/.env" <<EOF
ODT_PROJECT=opendevtrack-deploy-check
COMPOSE_PROFILES=proxy,keycloak
ODT_BACKEND_IMAGE=${DEPLOY_IMAGE:-opendevtrack-backend:deploy-check}
ODT_HOST=localhost
ODT_URL=$front
HTTPS_PORT=$HTTPS_PORT
HTTP_PORT=28081
KEYCLOAK_HOST=keycloak.localhost
KEYCLOAK_URL=http://keycloak:8080
IAM_CLIENT_SECRET=$(secret)
PUBLISHER_CLIENT_SECRET=$(secret)
ODT_BEARER_CLIENTS=opendevtrack-publisher
POSTGRES_PASSWORD=$(secret)
DB_PASSWORD=$(secret)
KEYCLOAK_DB_PASSWORD=$(secret)
REDIS_PASSWORD=$(secret)
ODT_SECRET_KEY=$(secret)
KEYCLOAK_ADMIN_PASSWORD=$(secret)
ADMIN_PASSWORD=Check-$(secret)
PASSWORD_BLACKLISTS=$(host_path "$work/password-blacklists")
PACKAGES_TOKEN=$PACKAGES_TOKEN
EOF
    umask 022
  fi
  set -a; . "$work/.env"; set +a
}

image() {
  if [ -n "${DEPLOY_IMAGE:-}" ]; then return 0; fi
  log "image of the back end, from backend/"
  umask 077
  printf '<settings><servers><server><id>authz-library</id><username>token</username><password>%s</password></server></servers></settings>\n' \
    "$PACKAGES_TOKEN" > "$work/maven-settings.xml"
  umask 022
  # BACKEND_JAR: a jar already built (CI, issue #47); the image takes it and Maven does not run in Docker
  local from=()
  if [ -n "${BACKEND_JAR:-}" ]; then
    mkdir -p "$work/dist"
    cp "$BACKEND_JAR" "$work/dist/app.jar"
    from=(--build-arg JAR=prebuilt --build-context dist="$(host_path "$work/dist")")
  fi
  # The output of the build goes to a file and is shown only when the build fails, with the messages of Maven
  # (issue #44: `docker build -q` printed nothing but the failed step).
  if ! docker build --progress=plain "${from[@]}" --secret id=maven-settings,src="$work/maven-settings.xml" \
      -t opendevtrack-backend:deploy-check "$root/backend" > "$work/image-build.log" 2>&1; then
    echo "the build of the image failed; the end of its output ($work/image-build.log):" >&2
    tail -n 120 "$work/image-build.log" >&2
    return 1
  fi
}

kcadm() {
  # MSYS_NO_PATHCONV: Git Bash on Windows would turn the path in the container into a path of the host
  # (so the paths of the host are given as Docker reads them)
  MSYS_NO_PATHCONV=1 docker compose --project-directory "$(host_path "$root/deploy/compose")" \
    -f "$(host_path "$root/deploy/compose/compose.yaml")" -f "$(host_path "$here/compose.yaml")" \
    --env-file "$(host_path "$work/.env")" exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"
}

up() {
  packages
  credentials
  image
  log "containers (./odt.sh up)"
  "$odt" up
  log "the administrator $admin in Keycloak, as an installation adds it in the admin console"
  kcadm config credentials --server http://localhost:8080 --realm master --user admin \
    --password "$KEYCLOAK_ADMIN_PASSWORD" > /dev/null
  kcadm create users -r opendevtrack -s username="$admin" -s enabled=true -s email="$admin@example.org" \
    -s emailVerified=true -s firstName=Ada -s lastName=Lovelace > /dev/null
  kcadm set-password -r opendevtrack --username "$admin" --new-password "$ADMIN_PASSWORD"
  log "./odt.sh first-admin MAIN $admin"
  "$odt" first-admin MAIN "$admin" "$admin@example.org"
  log "./odt.sh publish-ui"
  "$odt" publish-ui
  log "ready: $front ($admin, password ADMIN_PASSWORD in .work/.env)"
}

# curl as a browser: the certificates of the local authority of Caddy, and keycloak:8080 on the port of the host
browser() {
  curl -k --connect-to "keycloak:8080:127.0.0.1:$KEYCLOAK_TEST_PORT" -c "$work/cookies" -b "$work/cookies" "$@"
}

check() {
  credentials
  local failed=0 page action headers caps xsrf status key
  ok() { echo "ok   $1"; }
  fail() { echo "FAIL $1"; failed=1; }

  log "the pages over https, with Strict-Transport-Security"
  headers="$(curl -ks -D - -o /dev/null "$front/")"
  case "$headers" in *[Ss]trict-[Tt]ransport-[Ss]ecurity*) ok "HSTS on the pages" ;; *) fail "no HSTS on the pages" ;; esac
  if curl -ks "$front/runtime-config.json" | grep -q '"source": *"registry"'; then ok "runtime on the registry"; else
    fail "runtime-config.json without the registry"; fi

  log "the health and the metrics are not public"
  if curl -ks "$front/actuator/prometheus" | grep -q '^jvm_'; then fail "metrics through the proxy"; else ok "no metrics through the proxy"; fi

  log "login of $admin through Caddy and Keycloak"
  rm -f "$work/cookies"
  page="$(browser -fsSL "$front/bff/login?return=$front/")"
  action="$(printf '%s' "$page" | grep -o 'id="kc-form-login"[^>]*action="[^"]*"' | sed -e 's/.*action="//' -e 's/"$//' -e 's/&amp;/\&/g')"
  if [ -z "$action" ]; then fail "no login form of Keycloak"; return 1; fi
  case "$page" in */login/opendevtrack/*) ok "the login theme of OpenDevTrack" ;; *) fail "not the login theme" ;; esac
  browser -fsSL -o /dev/null --data-urlencode "username=$admin" --data-urlencode "password=$ADMIN_PASSWORD" "$action"
  if grep -q '__Host-SESSION_ID' "$work/cookies"; then ok "session cookie __Host-SESSION_ID"; else fail "no __Host-SESSION_ID"; fi
  browser -fs -o /dev/null "$front/bff/user"
  if browser -fs "$front/bff/user" | grep -q "\"$admin\""; then ok "/bff/user is $admin"; else fail "/bff/user"; fi

  log "permissions of the first administrator"
  caps="$(browser -fs "$front/authz/capabilities?app=opendevtrack")"
  case "$caps" in *'"odt.project.create"'*) ok "odt.project.create" ;; *) fail "odt.project.create missing: $caps" ;; esac

  log "a project, and the answers of the back end over https"
  xsrf="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$work/cookies")"
  # A new key at each run: letters only, from the clock
  key="D$(date +%s | tail -c 6 | tr 0-9 A-J)"
  status="$(browser -s -o /dev/null -w '%{http_code}' -H "Origin: $front" -H "X-XSRF-TOKEN: $xsrf" \
    -H 'Content-Type: application/json' -d "{\"key\":\"$key\",\"name\":\"First project $key\"}" "$front/api/projects")"
  if [ "$status" = 201 ]; then ok "POST /api/projects 201"; else fail "POST /api/projects $status"; fi
  headers="$(browser -s -D - -o /dev/null "$front/api/projects")"
  case "$headers" in *[Ss]trict-[Tt]ransport-[Ss]ecurity*) ok "HSTS from the back end (X-Forwarded-Proto https)" ;;
    *) fail "no HSTS from the back end" ;; esac

  log "the screens published in production"
  if browser -fs "$front/registry/apps/opendevtrack/documents/navigation/main/published?env=production" \
    | grep -q '"navigation"'; then ok "the navigation main is published in production"; else
    fail "no navigation published in production"; fi

  if [ "$failed" = 0 ]; then log "all checks passed"; else log "some checks failed"; return 1; fi
}

down() {
  credentials
  docker compose --project-directory "$root/deploy/compose" -f "$root/deploy/compose/compose.yaml" \
    -f "$here/compose.yaml" --env-file "$work/.env" --profile tools --profile proxy --profile keycloak down -v
}

case "${1:-}" in
  up) up ;;
  check) check ;;
  down) down ;;
  *) sed -n '2,12p' "$0" >&2; exit 2 ;;
esac
