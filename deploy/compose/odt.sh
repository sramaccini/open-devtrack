#!/usr/bin/env bash
# OpenDevTrack with Docker Compose (docs/admin/installation.md).
#
#   ./odt.sh up                                   start, or apply a change of .env or of the image
#   ./odt.sh first-admin TENANT USERNAME EMAIL    once: the first tenant, its global administrator (a user of
#                                                 Keycloak with that username) and the publisher of ui/
#   ./odt.sh publish-ui                           the screens of ui/ into the registry, after each upgrade
#   ./odt.sh status | logs [service] | down       state of the containers, their log, stop them (the data stay)
#
# The settings are in .env next to this file (ODT_ENV_FILE for another one); ODT_COMPOSE_FILES adds compose files,
# separated by colons (the check of e2e/deploy).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
env_file="${ODT_ENV_FILE:-$here/.env}"
[ -f "$env_file" ] || { echo "no $env_file: copy .env.example and set its values" >&2; exit 1; }

compose=(docker compose --project-directory "$here" -f "$here/compose.yaml" --env-file "$env_file")
if [ -n "${ODT_COMPOSE_FILES:-}" ]; then
  IFS=: read -ra extra <<< "$ODT_COMPOSE_FILES"
  for file in "${extra[@]}"; do compose+=(-f "$file"); done
fi

first_admin() {
  local tenant="${1:?tenant code, for example MAIN}" username="${2:?username of the administrator in Keycloak}"
  local email="${3:?email of the administrator}"
  # The schema of authz-library is written by the back end on its first start (Flyway)
  "${compose[@]}" exec -T postgres psql -U postgres -d opendevtrack -v ON_ERROR_STOP=1 \
    -v tenant="$tenant" -v username="$username" -v email="$email" <<'SQL'
SET search_path TO authz;
INSERT INTO tenant (code, description) VALUES (:'tenant', :'tenant') ON CONFLICT (code) DO NOTHING;
INSERT INTO app_user (username, email) VALUES (:'username', :'email') ON CONFLICT (username) DO NOTHING;
-- The user of the service account of the client opendevtrack-publisher (deploy/keycloak/realm-opendevtrack.json)
INSERT INTO app_user (username) VALUES ('service-account-opendevtrack-publisher') ON CONFLICT (username) DO NOTHING;
-- The administrator: global administrator of authz-library, admin and member of the tenant in OpenDevTrack (ADR-07,
-- ADR-22); the publisher: author of the screen definitions (ADR-03)
INSERT INTO user_role (user_id, role_id, tenant_id)
SELECT u.id, r.id, :'tenant' FROM app_user u JOIN app_role r ON
    (u.username = :'username' AND r.role_code IN ('GLOBAL_ADMIN', 'ODT_ADMIN', 'ODT_MEMBER')) OR
    (u.username = 'service-account-opendevtrack-publisher' AND r.role_code = 'ODT_UI_AUTHOR')
ON CONFLICT DO NOTHING;
SELECT u.username, r.role_code, ur.tenant_id FROM user_role ur
JOIN app_user u ON u.id = ur.user_id JOIN app_role r ON r.id = ur.role_id
WHERE ur.tenant_id = :'tenant' ORDER BY 1, 2;
SQL
}

case "${1:-}" in
  up) "${compose[@]}" up -d --wait ;;
  first-admin) shift; first_admin "$@" ;;
  publish-ui) "${compose[@]}" --profile tools run --rm publish ;;
  status) "${compose[@]}" ps ;;
  logs) shift; "${compose[@]}" logs -f "$@" ;;
  down) "${compose[@]}" down ;;
  *) sed -n '2,10p' "$0" >&2; exit 2 ;;
esac
