#!/usr/bin/env bash
# The public demo of OpenDevTrack (ADR-25 point 3; deploy/demo/README.md): deploy/compose with the file compose.yaml
# of this folder, three shared users, and the sample projects of stack/ added again every night.
#
#   deploy/demo/demo.sh up        start, or apply a change of .env or of the image (./odt.sh up with the demo file)
#   deploy/demo/demo.sh setup     once, after the first up: the realm of the demo, its users, ui/, the base snapshot
#                                 and the sample projects
#   deploy/demo/demo.sh reset     every night (cron): the database back to the base snapshot, Redis emptied, the
#                                 sample projects added again, with dates relative to the day
#   deploy/demo/demo.sh upgrade   after a change of ODT_BACKEND_IMAGE: reset to the base, up, ui/ published again,
#                                 a new base snapshot, the sample projects
#   deploy/demo/demo.sh hooks     what the echo of the webhooks got
#
# The settings are those of deploy/compose/.env (ODT_ENV_FILE for another one) with ECHO_HOST in addition. The
# base snapshot goes into deploy/demo/.work (gitignored).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
work="$here/.work"
odt="$root/deploy/compose/odt.sh"

export ODT_ENV_FILE="${ODT_ENV_FILE:-$root/deploy/compose/.env}"
export ODT_COMPOSE_FILES="$here/compose.yaml${ODT_COMPOSE_FILES:+:$ODT_COMPOSE_FILES}"
[ -f "$ODT_ENV_FILE" ] || { echo "no $ODT_ENV_FILE: copy deploy/compose/.env.example and set its values" >&2; exit 1; }
set -a; . "$ODT_ENV_FILE"; set +a
: "${ECHO_HOST:?set ECHO_HOST in $ODT_ENV_FILE: the public name of the echo of the webhooks}"

# The tenant and the users of the demo. Their passwords are public by design (ADR-25): they are in the README of
# the demo, nobody can change them, and every night everything else is erased.
tenant=DEMO
users=(
  # username     first   last     password            roles in the tenant
  "mario.rossi   Mario   Rossi    odt-demo-mario      ODT_MEMBER ODT_PROJECT_CREATOR"
  "anna.bianchi  Anna    Bianchi  odt-demo-anna       ODT_MEMBER"
  "giulia.verdi  Giulia  Verdi    odt-demo-giulia     ODT_MEMBER ODT_ADMIN TENANT_ADMIN"
)

front="$ODT_URL"
SAMPLES_PASSWORD=odt-demo-mario
. "$root/stack/samples.sh"

log() { printf '\n== %s %s\n' "$(date -u +%FT%TZ)" "$*"; }
# A path Docker understands, also from Git Bash on Windows (the local check)
host_path() { if command -v cygpath > /dev/null; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

compose() {
  local files=() file
  IFS=: read -ra files <<< "$ODT_COMPOSE_FILES"
  local args=(--project-directory "$(host_path "$root/deploy/compose")"
    -f "$(host_path "$root/deploy/compose/compose.yaml")")
  for file in "${files[@]}"; do args+=(-f "$(host_path "$file")"); done
  MSYS_NO_PATHCONV=1 docker compose "${args[@]}" --env-file "$(host_path "$ODT_ENV_FILE")" "$@"
}

kcadm() { compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"; }
psql_admin() { compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 "$@"; }

# The realm of deploy/keycloak with the settings of the demo, so that one visitor cannot lock out the others: no
# required actions, no account console, no reset of the password, no lockout after failed logins (the passwords are
# public anyway), and the notice on the login page.
realm() {
  log "the realm opendevtrack with the settings of the demo"
  kcadm config credentials --server http://localhost:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
    --password "$KEYCLOAK_ADMIN_PASSWORD" > /dev/null
  kcadm update realms/opendevtrack -s resetPasswordAllowed=false -s bruteForceProtected=false
  # The notice as the title of the login form: the theme shows the logo in place of the name of the realm
  kcadm create realms/opendevtrack/localization/en \
    -b '{"loginAccountTitle":"Demo: everything is erased every night, enter nothing private"}'
  kcadm create realms/opendevtrack/localization/it \
    -b '{"loginAccountTitle":"Demo: tutto viene cancellato ogni notte, non inserire nulla di privato"}'
  local action client id
  for action in $(kcadm get authentication/required-actions -r opendevtrack --fields alias --format csv --noquotes); do
    kcadm update "authentication/required-actions/$action" -r opendevtrack -s enabled=false -s defaultAction=false
  done
  for client in account account-console; do
    id="$(kcadm get clients -r opendevtrack -q clientId="$client" --fields id --format csv --noquotes)"
    [ -n "$id" ] && kcadm update "clients/$id" -r opendevtrack -s enabled=false
  done
  local user username first last password roles
  for user in "${users[@]}"; do
    read -r username first last password roles <<< "$user"
    id="$(kcadm get users -r opendevtrack -q username="$username" -q exact=true --fields id --format csv --noquotes)"
    if [ -z "$id" ]; then
      kcadm create users -r opendevtrack -s username="$username" -s enabled=true -s firstName="$first" \
        -s lastName="$last" -s email="$username@demo.invalid" -s emailVerified=true > /dev/null
    fi
    kcadm set-password -r opendevtrack --username "$username" --new-password "$password"
  done
}

# The tenant, the users and their roles in authz-library, and the publisher of ui/ (as ./odt.sh first-admin does,
# without a global administrator: the demo has none).
roles() {
  log "the tenant $tenant and the roles of the users"
  # The pairs (username, role), the publisher of ui/ first; the names are those of the table above, no input
  local user username first last password roles role pairs="('service-account-opendevtrack-publisher', 'ODT_UI_AUTHOR')"
  for user in "${users[@]}"; do
    read -r username first last password roles <<< "$user"
    for role in $roles; do pairs+=", ('$username', '$role')"; done
  done
  psql_admin -d opendevtrack -q <<SQL
SET search_path TO authz;
INSERT INTO tenant (code, description) VALUES ('$tenant', 'OpenDevTrack demo') ON CONFLICT (code) DO NOTHING;
WITH pairs (username, role) AS (VALUES $pairs)
INSERT INTO app_user (username, email)
SELECT DISTINCT username, CASE WHEN username LIKE 'service-account-%' THEN NULL ELSE username || '@demo.invalid' END
FROM pairs ON CONFLICT (username) DO NOTHING;
WITH pairs (username, role) AS (VALUES $pairs)
INSERT INTO user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, '$tenant' FROM pairs p
JOIN app_user u ON u.username = p.username JOIN app_role r ON r.role_code = p.role
ON CONFLICT DO NOTHING;
SQL
}

# The base snapshot: the schemas, the users and roles, and the screens of ui/ published, without any project
snapshot() {
  log "the base snapshot into .work/base.dump"
  mkdir -p "$work"
  compose exec -T postgres pg_dump -U postgres -Fc opendevtrack > "$work/base.dump.new"
  mv "$work/base.dump.new" "$work/base.dump"
}

# The database back to the base snapshot, as postgres so that the owners stay those of the snapshot, and Redis
# emptied (the sessions and the rate limits): every visitor is signed out.
restore() {
  [ -s "$work/base.dump" ] || { echo "no $work/base.dump: run setup first" >&2; exit 1; }
  log "the database back to the base snapshot"
  compose stop backend
  psql_admin -q -c "DROP DATABASE IF EXISTS opendevtrack WITH (FORCE)" \
    -c "CREATE DATABASE opendevtrack OWNER opendevtrack"
  compose exec -T postgres pg_restore -U postgres -d opendevtrack --exit-on-error < "$work/base.dump"
  compose exec -T redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli FLUSHALL' > /dev/null
}

# ./odt.sh up with the demo file. On the first start Keycloak can answer 503 while it imports the realm, and the back
# end then restarts (deploy/compose/compose.yaml): up waits a second time.
up() { "$odt" up || { log "the back end was not ready yet: waiting again"; "$odt" up; }; }

samples() {
  mkdir -p "$work"
  seed
  # The cookies of the session of mario.rossi are not kept
  rm -f "$work"/*.cookies
}

case "${1:-}" in
  up) up ;;
  setup)
    up
    realm
    roles
    log "ui/ published (./odt.sh publish-ui)"
    "$odt" publish-ui
    snapshot
    samples
    log "ready: $front" ;;
  reset)
    restore
    up
    samples
    log "reset done" ;;
  upgrade)
    restore
    up
    log "ui/ published again (./odt.sh publish-ui)"
    "$odt" publish-ui
    snapshot
    samples
    log "upgrade done" ;;
  hooks) compose logs -f echo ;;
  *) sed -n '2,15p' "$0" >&2; exit 2 ;;
esac
