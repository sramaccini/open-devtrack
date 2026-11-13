#!/usr/bin/env bash
# OpenDevTrack in Docker, for trying it and reviewing it (stack/README.md).
#
#   stack/stack.sh up       generate the credentials (first time), build the back end image, start everything,
#                           publish ui/ to the registry and add the sample projects (first time)
#   stack/stack.sh publish  publish ui/ again, after a change of the screens
#   stack/stack.sh seed     add the sample projects, when they are not there
#   stack/stack.sh info     the addresses and the users
#   stack/stack.sh logs     follow the log of the back end
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
export KEYCLOAK_PORT APP_PORT FRONT_PORT MAILPIT_PORT

front="http://localhost:$FRONT_PORT"
compose=(docker compose -f "$here/compose.yaml" --env-file "$work/.env")

log() { printf '\n== %s\n' "$*"; }

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

# Sign in through the runtime as a browser does; the cookies go into .work/<user>.cookies.
login() {
  local user="$1" jar="$work/$1.cookies" page action
  rm -f "$jar"
  page="$(curl -fsSL -c "$jar" -b "$jar" "$front/bff/login?return=$front/")"
  action="$(printf '%s' "$page" | grep -o 'id="kc-form-login"[^>]*action="[^"]*"' | sed -e 's/.*action="//' -e 's/"$//' -e 's/&amp;/\&/g')"
  [ -n "$action" ] || { echo "no Keycloak login form for $user" >&2; return 1; }
  curl -fsSL -o /dev/null -c "$jar" -b "$jar" --data-urlencode "username=$user" \
    --data-urlencode "password=$DEV_USER_PASSWORD" "$action"
  # A first call gives the XSRF-TOKEN cookie, as in the browser.
  curl -fs -o /dev/null -c "$jar" -b "$jar" "$front/bff/user"
}

# A call of the API as the signed-in user: method, path, JSON body; prints the answer.
api() {
  local user="$1" method="$2" path="$3" body="${4:-}" xsrf
  xsrf="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$work/$user.cookies")"
  curl -fsS -X "$method" -b "$work/$user.cookies" -c "$work/$user.cookies" -H "Origin: $front" \
    -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' -H 'Accept: application/json' \
    ${body:+-d "$body"} "$front$path"
}

field() { grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4; }
num() { grep -o "\"$1\":[0-9]*" | head -1 | cut -d: -f2; }
day() { date -u -d "$1 days" +%F; }
# A JSON string, or null when empty
str() { if [ -n "$1" ]; then printf '"%s"' "$1"; else printf null; fi; }

issue() {
  # project, type, priority, assignee, reviewer, due in days, labels (comma separated), summary, description
  local labels="" l due=""
  for l in ${7//,/ }; do labels="$labels${labels:+,}\"$l\""; done
  [ -n "$6" ] && due="$(day "$6")"
  api mario.rossi POST "/api/projects/$1/issues" "{\"type\":\"$2\",\"priority\":\"$3\",\"assignee\":$(str "$4"),
    \"reviewer\":$(str "$5"),\"dueOn\":$(str "$due"),\"labels\":[$labels],\"summary\":\"$8\",\"description\":\"$9\"}" \
    > /dev/null
}

go() {
  local version
  version="$(api mario.rossi GET "/api/issues/$1" | num version)"
  api mario.rossi POST "/api/issues/$1/transitions" "{\"transition\":\"$2\",\"version\":$version}" > /dev/null
}

seed() {
  login mario.rossi
  if api mario.rossi GET /api/projects/WEB > /dev/null 2>&1; then
    log "sample projects already there"
    return 0
  fi
  log "sample projects (WEB, MOB) as mario.rossi"
  api mario.rossi POST /api/projects '{"key":"WEB","name":"Website relaunch"}' > /dev/null
  api mario.rossi POST /api/projects/WEB/members '{"username":"anna.bianchi","role":"developer"}' > /dev/null
  issue WEB epic high mario.rossi "" 30 "" "New website" "The relaunch of the public website, in two sprints."
  issue WEB story high anna.bianchi mario.rossi 5 "frontend" "Home page with the new brand" \
    "The hero, the three features and the call to action, from the mock-ups."
  issue WEB story medium anna.bianchi mario.rossi 9 "frontend,i18n" "Pricing page in English and Italian" \
    "Plans side by side, yearly and monthly prices."
  issue WEB bug highest mario.rossi anna.bianchi -1 "frontend" "The menu does not open on Safari" \
    "On Safari 18 the menu button does nothing; Chrome and Firefox are fine."
  issue WEB task medium mario.rossi "" -2 "ops" "Redirects from the old addresses" \
    "Every page of the old site answers 301 to its new address."
  issue WEB task low anna.bianchi "" 14 "seo" "Sitemap and robots.txt" "Generated at each build."
  issue WEB bug medium "" "" "" "frontend" "Footer links overlap on small screens" "Below 360 pixels wide."
  issue WEB story medium "" "" 20 "content" "Customer stories" "Three stories with quotes and logos."
  issue WEB task low mario.rossi "" "" "ops" "Analytics without cookies" "A tool that needs no consent banner."
  issue WEB task medium anna.bianchi "" -5 "content" "Copy of the about page" "Reviewed by marketing."

  go WEB-2 start; go WEB-4 start; go WEB-5 start; go WEB-10 start; go WEB-10 finish
  api mario.rossi POST /api/issues/WEB-4/links '{"relation":"blocks","issue":"WEB-2"}' > /dev/null
  api mario.rossi POST /api/issues/WEB-4/comments \
    '{"body":"@anna.bianchi it happens only with the reduced motion setting on."}' > /dev/null
  api mario.rossi POST /api/issues/WEB-2/comments '{"body":"The mock-ups are attached to the epic."}' > /dev/null

  local sprint key points=(5 3 2 3 1)
  sprint="$(api mario.rossi POST /api/projects/WEB/sprints "{\"name\":\"Sprint 1\",\"goal\":\"Home and pricing online\",
    \"startsOn\":\"$(day -4)\",\"endsOn\":\"$(day 10)\"}" | field id)"
  for key in WEB-2 WEB-3 WEB-4 WEB-5 WEB-10; do
    api mario.rossi POST "/api/projects/WEB/backlog/cards/$key/move" "{\"column\":\"$sprint\"}" > /dev/null
    api mario.rossi PUT "/api/issues/$key/plan" "{\"points\":${points[0]}}" > /dev/null
    points=("${points[@]:1}")
  done
  api mario.rossi POST "/api/sprints/$sprint/start" '{}' > /dev/null
  api mario.rossi POST /api/projects/WEB/boards '{"name":"Sprint board","kind":"scrum","lane":"priority",
    "columns":"To do: todo\nDoing: in_progress | 3\nDone: done"}' > /dev/null
  api mario.rossi POST /api/projects/WEB/sprints "{\"name\":\"Sprint 2\",\"goal\":\"Content and SEO\",
    \"startsOn\":\"$(day 11)\",\"endsOn\":\"$(day 25)\"}" > /dev/null

  api mario.rossi POST /api/projects '{"key":"MOB","name":"Mobile app"}' > /dev/null
  api mario.rossi POST /api/projects/MOB/members '{"username":"anna.bianchi","role":"admin"}' > /dev/null
  issue MOB story high anna.bianchi "" 12 "android" "Sign in with the company account" "Through the browser of the phone."
  issue MOB bug high mario.rossi anna.bianchi 2 "ios" "Crash when the photo is too large" "Above 20 MB."
  issue MOB task medium "" "" "" "release" "Store screenshots" "Phone and tablet, light and dark."
  go MOB-2 start
}

info() {
  log "OpenDevTrack: $front"
  echo "users mario.rossi and anna.bianchi, password: $DEV_USER_PASSWORD"
  echo "API:      http://localhost:$APP_PORT/api (docs/api.md); service account opendevtrack-ci, secret CI_CLIENT_SECRET in stack/.work/.env"
  echo "mails:    http://localhost:$MAILPIT_PORT"
  echo "webhooks: http://echo:8080/anything as the address, then stack.sh hooks to see what it got"
  echo "Keycloak: http://localhost:$KEYCLOAK_PORT (admin, password KEYCLOAK_ADMIN_PASSWORD in stack/.work/.env)"
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
  down) credentials; "${compose[@]}" down ;;
  reset) credentials; "${compose[@]}" down -v ;;
  *) echo "usage: $0 up | publish | seed | info | logs | hooks | down | reset" >&2; exit 2 ;;
esac
