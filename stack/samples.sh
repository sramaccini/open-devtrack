# The sample projects of stack/ (stack/README.md, «The sample projects»), as a user adds them through the BFF: sourced
# by stack/stack.sh and by deploy/demo/demo.sh, whose nightly reset adds them again (ADR-25 point 3).
#
# Needs: front, the address of OpenDevTrack as the browser sees it; work, a folder for the cookies;
# SAMPLES_PASSWORD, the password of mario.rossi; SAMPLES_CURL_ARGS, more options of curl for a local check
# (-k, --connect-to).
# The users mario.rossi (who creates the projects, so their admin) and anna.bianchi must exist, and log() be defined.

# Sign in through the BFF as a browser does; the cookies go into $work/<user>.cookies.
login() {
  local user="$1" jar="$work/$1.cookies" page action
  rm -f "$jar"
  page="$(curl ${SAMPLES_CURL_ARGS:-} -fsSL -c "$jar" -b "$jar" "$front/bff/login?return=$front/")"
  action="$(printf '%s' "$page" | grep -o 'id="kc-form-login"[^>]*action="[^"]*"' | sed -e 's/.*action="//' -e 's/"$//' -e 's/&amp;/\&/g')"
  [ -n "$action" ] || { echo "no Keycloak login form for $user" >&2; return 1; }
  curl ${SAMPLES_CURL_ARGS:-} -fsSL -o /dev/null -c "$jar" -b "$jar" --data-urlencode "username=$user" \
    --data-urlencode "password=$SAMPLES_PASSWORD" "$action"
  # A first call gives the XSRF-TOKEN cookie, as in the browser.
  curl ${SAMPLES_CURL_ARGS:-} -fs -o /dev/null -c "$jar" -b "$jar" "$front/bff/user"
}

# A call of the API as the signed-in user: method, path, JSON body; prints the answer.
api() {
  local user="$1" method="$2" path="$3" body="${4:-}" xsrf
  xsrf="$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$work/$user.cookies")"
  curl ${SAMPLES_CURL_ARGS:-} -fsS -X "$method" -b "$work/$user.cookies" -c "$work/$user.cookies" \
    -H "Origin: $front" -H "X-XSRF-TOKEN: $xsrf" -H 'Content-Type: application/json' -H 'Accept: application/json' \
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
