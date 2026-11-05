#!/usr/bin/env bash
# Integration test of the Storage Path Monitor plugin against a live Morpheus appliance.
#
#   scripts/integration-test.sh [--jar <file>] [--restore-jar <file>] [--preflight]
#
# Installs the plugin jar, checks it (status, settings, assets, route, permissions, cluster page, disable/enable)
# and afterwards puts the appliance back into the state it was in before: the previously installed version with its
# settings and enabled flag, or no plugin at all. If that state cannot be restored for sure, the test stops before it
# changes anything. Connection and options only come from environment variables – see docs/integration-test.md.
#
# Exit codes: 0 all checks passed · 1 checks failed (appliance restored) · 2 stopped before any change
#             3 restoring the previous state failed – check the appliance by hand · 64 usage error

# jq programs and `bash -c` snippets reference their own variables inside single quotes on purpose
# shellcheck disable=SC2016

set -Eeuo pipefail

PLUGIN_CODE=storage-path-monitor
REPO_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)

# --- options ------------------------------------------------------------------------------------------------------

usage() { sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 64; }

TEST_JAR=${SPM_TEST_JAR:-}
RESTORE_JAR=${SPM_RESTORE_JAR:-}
PREFLIGHT_ONLY=0
while [[ $# -gt 0 ]]; do
  case $1 in
    --jar) [[ -n ${2:-} ]] || usage; TEST_JAR=$2; shift 2 ;;
    --restore-jar) [[ -n ${2:-} ]] || usage; RESTORE_JAR=$2; shift 2 ;;
    --preflight) PREFLIGHT_ONLY=1; shift ;;
    -h|--help) usage ;;
    *) echo "unknown argument: $1" >&2; usage ;;
  esac
done

PREFIX=${TEST_PREFIX:-${LAB_TEST_PREFIX:-spmtest-}}
TEST_NAME="${PREFIX}spm-it"
LOAD_TIMEOUT=${SPM_TEST_LOAD_TIMEOUT:-180}
RUN_TIMEOUT=${SPM_TEST_RUN_TIMEOUT:-240}
TEST_INTERVAL=15   # lowest allowed interval, so that the test does not wait long for collection runs

# --- output -------------------------------------------------------------------------------------------------------

PASSED=0 FAILED=0 WARNINGS=0
FAILURES=()
step() { printf '\n== %s\n' "$*"; }
info() { printf '   %s\n' "$*"; }
pass() { PASSED=$((PASSED + 1)); printf '   ok    %s\n' "$*"; }
fail() { FAILED=$((FAILED + 1)); FAILURES+=("$*"); printf '   FAIL  %s\n' "$*"; }
warn() { WARNINGS=$((WARNINGS + 1)); printf '   warn  %s\n' "$*"; }
abort() { printf '\nSTOP: %s\n' "$*" >&2; exit 2; }
# check <description> <command…> – pass if the command succeeds
check() { local d=$1; shift; if "$@" >/dev/null; then pass "$d"; else fail "$d"; fi; }

# --- environment --------------------------------------------------------------------------------------------------

for tool in curl jq git; do
  command -v "$tool" >/dev/null || abort "required tool missing: $tool"
done
command -v unzip >/dev/null || command -v jar >/dev/null || abort "required tool missing: unzip (or jar from a JDK)"
[[ -n ${MORPHEUS_URL:-} ]] || abort "MORPHEUS_URL is not set"
[[ -n ${MORPHEUS_TOKEN:-} ]] || abort "MORPHEUS_TOKEN is not set"
BASE=${MORPHEUS_URL%/}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT   # replaced by on_exit below
# Secrets never go on a command line (visible in the process list): the token header and request bodies are passed
# as files written by the shell itself
(umask 077 && printf 'Authorization: Bearer %s\n' "$MORPHEUS_TOKEN" >"$TMP/auth")
CURL=(curl -sS --max-time 60)
if [[ ${MORPHEUS_INSECURE:-0} == 1 ]]; then
  CURL+=(-k)
elif [[ -n ${MORPHEUS_CACERT:-} ]]; then
  [[ -r $MORPHEUS_CACERT ]] || abort "MORPHEUS_CACERT is not readable: $MORPHEUS_CACERT"
  CURL+=(--cacert "$MORPHEUS_CACERT")
fi

# api <method> <path> [json] – API call with the token; body → $TMP/body, prints the HTTP status
api() {
  local method=$1 path=$2 data=${3:-}
  local args=(-X "$method" -H "@$TMP/auth" -o "$TMP/body" -w '%{http_code}')
  if [[ -n $data ]]; then
    (umask 077 && printf '%s' "$data" >"$TMP/request")
    args+=(-H 'Content-Type: application/json' --data-binary "@$TMP/request")
  fi
  "${CURL[@]}" "${args[@]}" "$BASE$path" || echo 000
}
# api_ok <method> <path> [json] – like api, fails unless 2xx and success is not false
api_ok() {
  local code; code=$(api "$@")
  [[ $code == 2* ]] && ! jq -e '.success == false' "$TMP/body" >/dev/null 2>&1
}

# Current plugin entry (one JSON object) or nothing
plugin_state() {
  local code; code=$(api GET "/api/plugins?max=1000")
  [[ $code == 200 ]] || { echo "GET /api/plugins failed (HTTP $code)" >&2; return 1; }
  jq -c --arg c "$PLUGIN_CODE" '[.plugins[] | select(.code == $c)
    | {id, version, enabled, status, statusMessage, config: (.config // {})}] | first // empty' "$TMP/body"
}
# Fields that must be the same before and after the test
comparable() { jq -cS '{id, version, enabled, status, config}' <<<"$1"; }

# manifest <jar> <attribute> – reads the jar manifest with unzip or, if missing, with the JDK's jar tool
manifest() {
  local file
  file=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
  if command -v unzip >/dev/null; then
    unzip -p "$file" META-INF/MANIFEST.MF 2>/dev/null
  else
    local dir; dir=$(mktemp -d -p "$TMP")
    (cd "$dir" && jar xf "$file" META-INF/MANIFEST.MF 2>/dev/null && cat META-INF/MANIFEST.MF)
  fi | tr -d '\r' | sed -n "s/^$2: //p" | head -n1
}

upload() {
  "${CURL[@]}" --max-time 300 -H "@$TMP/auth" -F "file=@$1" -o "$TMP/upload" \
    -w '%{http_code}' "$BASE/api/plugins/upload" || echo 000
}
# wait_loaded <version> – waits until the plugin reports this version as loaded; prints the entry
wait_loaded() {
  local version=$1 deadline=$((SECONDS + LOAD_TIMEOUT)) s=""
  while ((SECONDS < deadline)); do
    s=$(plugin_state || true)
    if [[ -n $s ]] && [[ $(jq -r .version <<<"$s") == "$version" ]]; then
      case $(jq -r .status <<<"$s") in
        loaded) echo "$s"; return 0 ;;
        error) echo "$s"; return 1 ;;
      esac
    fi
    sleep 3
  done
  echo "$s"; return 1
}
plugin_put() { api_ok PUT "/api/plugins/$1" "$(jq -cn --argjson p "$2" '{plugin: $p}')"; }

# --- UI session (plugin routes do not accept API tokens) ----------------------------------------------------------

UI_USER="" UI_PASSWORD=""
COOKIES="$TMP/cookies"
ui_login() {
  rm -f "$COOKIES"
  local csrf
  csrf=$("${CURL[@]}" -c "$COOKIES" -b "$COOKIES" "$BASE/login/auth" \
    | grep -o 'name="_csrf"[^>]*value="[^"]*"' | head -n1 | sed 's/.*value="//; s/"$//') || true
  [[ -n $csrf ]] || return 1
  local target
  target=$(printf '%s' "$UI_PASSWORD" | "${CURL[@]}" -c "$COOKIES" -b "$COOKIES" -o /dev/null -w '%{redirect_url}' \
    --data-urlencode "username=$UI_USER" --data-urlencode "password@-" --data-urlencode "_csrf=$csrf" \
    "$BASE/login/process") || return 1
  [[ -n $target && $target != *"/login"* ]]
}
ui_logout() { [[ -f $COOKIES ]] && "${CURL[@]}" -b "$COOKIES" -o /dev/null "$BASE/logout" || true; rm -f "$COOKIES"; }
# ui_get <path> [cookies] – body → $TMP/page, prints "<status> <content type>"
ui_get() {
  "${CURL[@]}" -b "${2:-$COOKIES}" -o "$TMP/page" -w '%{http_code} %{content_type}' "$BASE$1" || echo "000 -"
}
route() { ui_get "/plugin/$PLUGIN_CODE/paths?$1"; }

# --- restore and clean up (also on errors and interrupts) ---------------------------------------------------------

INITIAL=""          # plugin entry before the test, empty if not installed
CHANGED=0           # 1 as soon as the plugin was touched
RESTORED=0
WORKTREE=""
ROLE_ID="" USER_ID=""

# test_object_ids <users|roles> – ids of objects named exactly $TEST_NAME; fails if the list cannot be read
test_object_ids() {
  local field=username
  [[ $1 == roles ]] && field=authority
  [[ $(api GET "/api/$1?max=100&phrase=$(jq -rn --arg n "$TEST_NAME" '$n | @uri')") == 200 ]] || return 1
  jq -er --arg n "$TEST_NAME" --arg k "$1" --arg f "$field" '.[$k] | arrays | map(select(.[$f] == $n) | .id) | .[]?' \
    "$TMP/body" 2>/dev/null || jq -e --arg k "$1" '.[$k] | arrays' "$TMP/body" >/dev/null 2>&1
}
remove_test_objects() {
  local ids id
  ui_logout
  # own objects of this run and leftovers of aborted runs (exact names only); users first, they hold the role
  ids=$(test_object_ids users) || ids=""
  for id in $(printf '%s\n' "$ids" "$USER_ID" | sort -u); do api_ok DELETE "/api/users/$id" || true; done
  ids=$(test_object_ids roles) || ids=""
  for id in $(printf '%s\n' "$ids" "$ROLE_ID" | sort -u); do api_ok DELETE "/api/roles/$id" || true; done
  USER_ID="" ROLE_ID=""
  local users roles
  users=$(test_object_ids users) || return 1
  roles=$(test_object_ids roles) || return 1
  [[ -z $users && -z $roles ]]
}

restore_plugin() {
  local now id
  if [[ -z $INITIAL ]]; then
    now=$(plugin_state) || return 1
    if [[ -n $now ]]; then
      id=$(jq -r .id <<<"$now")
      api_ok DELETE "/api/plugins/$id" || { info "removing the plugin failed (HTTP response: $(head -c 300 "$TMP/body"))"; return 1; }
    fi
    sleep 2
    now=$(plugin_state) || return 1
    [[ -z $now ]] || { info "plugin is still installed: $now"; return 1; }
    info "plugin removed again (it was not installed before)"
    return 0
  fi
  local version code
  version=$(jq -r .version <<<"$INITIAL")
  code=$(upload "$RESTORE_JAR")
  { [[ $code == 200 ]] && jq -e '.success == true' "$TMP/upload" >/dev/null 2>&1; } \
    || { info "upload of $version failed (HTTP $code): $(head -c 300 "$TMP/upload")"; return 1; }
  sleep 5   # with the same version as under test, the entry looks loaded before the reload has finished
  now=$(wait_loaded "$version") || { info "version $version did not load: $now"; return 1; }
  id=$(jq -r .id <<<"$now")
  # PUT replaces the whole settings object; the upload enables the plugin, so the flag is set afterwards
  plugin_put "$id" "$(jq -c '{config}' <<<"$INITIAL")" || { info "restoring the settings failed"; return 1; }
  plugin_put "$id" "$(jq -c '{enabled}' <<<"$INITIAL")" || { info "restoring the enabled flag failed"; return 1; }
  sleep 3
  now=$(plugin_state) || return 1
  if [[ $(comparable "$now") != $(comparable "$INITIAL") ]]; then
    info "before: $(comparable "$INITIAL")"
    info "after:  $(comparable "$now")"
    return 1
  fi
  info "restored: $(comparable "$now")"
}

ERR_AT=""
on_exit() {
  local rc=$?
  set +e
  trap - EXIT ERR
  trap '' INT TERM   # a second Ctrl+C must not interrupt the restore
  if [[ ${PREFLIGHT_DONE:-} == 1 && $PREFLIGHT_ONLY == 0 ]] && ! [[ $rc =~ ^(0|1|2|3|130)$ ]]; then
    fail "test aborted by an unexpected error${ERR_AT:+ at line $ERR_AT} (exit code $rc)"
    rc=1
  fi
  if ((RESTORED == 0)); then
    RESTORED=1
    if [[ $PREFLIGHT_ONLY == 0 && -n ${PREFLIGHT_DONE:-} ]]; then
      step "Restore"
      if remove_test_objects; then pass "test user and role removed"; else fail "test user or role left over ($TEST_NAME)"; rc=3; fi
      if ((CHANGED == 1)); then
        if restore_plugin; then pass "plugin back in its previous state"
        else fail "plugin NOT restored – check it by hand (state before: ${INITIAL:-not installed})"; rc=3; fi
      fi
    fi
  fi
  if [[ -n $WORKTREE ]]; then
    git -C "$REPO_DIR" worktree remove --force "$WORKTREE" >/dev/null 2>&1
    rm -rf "$TMP"
    git -C "$REPO_DIR" worktree prune >/dev/null 2>&1
  fi
  rm -rf "$TMP"
  if [[ ${PREFLIGHT_DONE:-} == 1 && $PREFLIGHT_ONLY == 0 ]]; then
    printf '\n== Result: %d passed, %d failed, %d warnings\n' "$PASSED" "$FAILED" "$WARNINGS"
    local f; for f in "${FAILURES[@]}"; do printf '   - %s\n' "$f"; done
    ((rc == 0 && FAILED > 0)) && rc=1
  fi
  exit "$rc"
}
trap on_exit EXIT
trap 'ERR_AT=$LINENO' ERR
trap 'echo; echo "interrupted" >&2; exit 130' INT TERM

# --- 1. preflight (no changes) ------------------------------------------------------------------------------------

step "Preflight"
if [[ -z $TEST_JAR ]]; then
  version=$(sed -n 's/^version=//p' "$REPO_DIR/gradle.properties")
  info "building $PLUGIN_CODE $version"
  (cd "$REPO_DIR" && ./gradlew -q shadowJar) || abort "build failed"
  TEST_JAR="$REPO_DIR/build/libs/$PLUGIN_CODE-$version-all.jar"
fi
[[ -r $TEST_JAR ]] || abort "jar not found: $TEST_JAR"
[[ $(manifest "$TEST_JAR" Morpheus-Code) == "$PLUGIN_CODE" ]] || abort "$TEST_JAR is not a $PLUGIN_CODE plugin"
TEST_VERSION=$(manifest "$TEST_JAR" Plugin-Version)
info "jar under test: $TEST_JAR ($TEST_VERSION)"

code=$(api GET /api/whoami)
[[ $code == 200 ]] || abort "appliance not reachable or token rejected (GET /api/whoami → HTTP $code)"
info "appliance $BASE, API user $(jq -r '.user.username' "$TMP/body")"
INITIAL=$(plugin_state) || abort "cannot read the installed plugins"

if [[ -z $INITIAL ]]; then
  info "installed before: none – the plugin is removed again afterwards"
else
  info "installed before: $(comparable "$INITIAL")"
  [[ $(jq -r .status <<<"$INITIAL") == loaded ]] \
    || abort "the installed plugin is not loaded (status $(jq -r .status <<<"$INITIAL")) – fix that first"
  prev=$(jq -r .version <<<"$INITIAL")
  if [[ -z $RESTORE_JAR ]]; then
    tag="v$prev"
    git -C "$REPO_DIR" rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1 \
      || abort "no jar to restore version $prev: set SPM_RESTORE_JAR (or --restore-jar), or provide git tag $tag"
    info "building version $prev from tag $tag"
    WORKTREE="$TMP/restore-src"
    git -C "$REPO_DIR" worktree add -q --detach "$WORKTREE" "$tag" >/dev/null 2>&1 || abort "cannot check out $tag"
    (cd "$WORKTREE" && ./gradlew -q shadowJar) >/dev/null 2>&1 || abort "building $tag failed"
    RESTORE_JAR="$TMP/$PLUGIN_CODE-$prev-restore.jar"
    cp "$WORKTREE/build/libs/$PLUGIN_CODE-$prev-all.jar" "$RESTORE_JAR" 2>/dev/null || abort "no jar built from $tag"
  fi
  [[ -r $RESTORE_JAR ]] || abort "restore jar not readable: $RESTORE_JAR"
  [[ $(manifest "$RESTORE_JAR" Morpheus-Code) == "$PLUGIN_CODE" ]] || abort "restore jar is not a $PLUGIN_CODE plugin"
  [[ $(manifest "$RESTORE_JAR" Plugin-Version) == "$prev" ]] \
    || abort "restore jar has version $(manifest "$RESTORE_JAR" Plugin-Version), installed is $prev"
  info "restore jar: $RESTORE_JAR ($prev)"
fi
PREFLIGHT_DONE=1
if [[ $PREFLIGHT_ONLY == 1 ]]; then echo; echo "preflight passed – nothing changed"; exit 0; fi

# --- 2. test user ---------------------------------------------------------------------------------------------------

step "Test user"
remove_test_objects || abort "cannot remove leftovers of an earlier run ($TEST_NAME)"
api_ok POST /api/roles "$(jq -cn --arg n "$TEST_NAME" \
  '{role: {authority: $n, description: "Storage Path Monitor integration test", roleType: "user"}}')" \
  || abort "cannot create test role (HTTP response: $(head -c 300 "$TMP/body"))"
ROLE_ID=$(jq -r '.role.id' "$TMP/body")
set_perm() { api_ok PUT "/api/roles/$ROLE_ID/update-permission" "$(jq -cn --arg c "$1" --arg a "$2" '{permissionCode: $c, access: $a}')"; }
{ set_perm infrastructure-storage read && set_perm infrastructure-cluster read && set_perm admin-servers read \
  && api_ok PUT "/api/roles/$ROLE_ID/update-group" '{"allGroups":true,"access":"read"}'; } \
  || abort "cannot set permissions of the test role"
UI_USER=$TEST_NAME
UI_PASSWORD="Spm-$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)-7x"
# the password reaches jq through its environment, not its command line
api_ok POST /api/users "$(SPM_IT_PASSWORD=$UI_PASSWORD jq -cn --arg u "$UI_USER" --argjson r "$ROLE_ID" \
  '{user: {username: $u, email: ($u + "@example.invalid"), firstName: "Storage Path Monitor", lastName: "Test",
           password: env.SPM_IT_PASSWORD, roles: [{id: $r}]}}')" \
  || abort "cannot create test user (HTTP response: $(jq -c 'del(.user.password)' "$TMP/body" 2>/dev/null | head -c 300))"
USER_ID=$(jq -r '.user.id' "$TMP/body")
info "role and user $TEST_NAME (read: storage, clusters, compute; all groups)"

# --- 3. install -----------------------------------------------------------------------------------------------------

step "Install $TEST_VERSION"
CHANGED=1
UPLOAD_AT=$(date -u +%Y-%m-%dT%H:%M:%S)
code=$(upload "$TEST_JAR")
sleep 3
if [[ $code == 200 ]] && jq -e '.success == true' "$TMP/upload" >/dev/null; then pass "upload accepted"
else fail "upload rejected (HTTP $code): $(head -c 300 "$TMP/upload")"; exit 1; fi
if STATE=$(wait_loaded "$TEST_VERSION"); then pass "status loaded, version $TEST_VERSION"
else fail "plugin did not load: $STATE"; exit 1; fi
PLUGIN_ID=$(jq -r .id <<<"$STATE")
check "enabled after upload" jq -e '.enabled == true' <<<"$STATE"
[[ -n $INITIAL ]] && check "plugin id kept ($PLUGIN_ID)" test "$PLUGIN_ID" = "$(jq -r .id <<<"$INITIAL")"

# --- 4. settings ----------------------------------------------------------------------------------------------------

step "Settings"
api GET "/api/plugins/$PLUGIN_ID" >/dev/null
check "settings intervalSeconds, parallelHosts, commandTimeoutSeconds offered" jq -e \
  '[.plugin.optionTypes[]?.fieldName] | contains(["intervalSeconds", "parallelHosts", "commandTimeoutSeconds"])' "$TMP/body"
TEST_CONFIG=$(jq -cn --arg i "$TEST_INTERVAL" '{intervalSeconds: $i, parallelHosts: "2", commandTimeoutSeconds: "60"}')
check "settings saved" plugin_put "$PLUGIN_ID" "{\"config\":$TEST_CONFIG}"
STATE=$(plugin_state) || STATE='{}'
check "settings read back" jq -e --argjson c "$TEST_CONFIG" '.config == $c' <<<"$STATE"

# --- 5. assets and anonymous access --------------------------------------------------------------------------------

step "Assets and anonymous access"
for asset in storage-path-monitor.js storage-path-monitor.css; do
  r=$(ui_get "/assets/plugin/$PLUGIN_CODE/$asset" /dev/null)
  check "asset $asset served (HTTP ${r%% *})" test "${r%% *}" = 200
done
r=$(route "clusterId=1" | cut -d' ' -f1)
check "route without session redirects to the login (HTTP $r)" test "$r" = 302

# --- 6. route -------------------------------------------------------------------------------------------------------

step "Route GET /plugin/$PLUGIN_CODE/paths"
check "test user can sign in" ui_login
if [[ -n ${SPM_TEST_CLUSTER_ID:-} ]]; then
  CLUSTERS=$SPM_TEST_CLUSTER_ID
else
  api GET "/api/clusters?max=100" >/dev/null
  CLUSTERS=""
  for id in $(jq -r '.clusters[]?.id' "$TMP/body"); do
    [[ $(route "clusterId=$id&hosts=false" | cut -d' ' -f1) == 200 ]] && CLUSTERS+="$id "
  done
fi
read -r -a cluster_ids <<<"$CLUSTERS"
CLUSTERS="${cluster_ids[*]}"   # single spaces, no leading or trailing blank
if [[ -z $CLUSTERS ]]; then
  warn "no HVM cluster found – route data checks skipped (set SPM_TEST_CLUSTER_ID to name one)"
else
  info "HVM clusters: $CLUSTERS"
fi

r=$(route "")
check "missing clusterId → 400 (HTTP ${r%% *})" test "${r%% *}" = 400
r=$(route "clusterId=abc")
check "clusterId not a number → 400 (HTTP ${r%% *})" test "${r%% *}" = 400
r=$(route "clusterId=2147483647")
check "unknown cluster → 404 JSON (HTTP ${r%% *})" \
  bash -c '[[ $1 == 404 ]] && jq -e ".error == \"not found\"" "$2" >/dev/null' _ "${r%% *}" "$TMP/page"
if [[ -n $CLUSTERS ]]; then
  first=${CLUSTERS%% *}
  r=$(route "clusterId=$first&hosts=maybe")
  check "hosts not true/false → 400 (HTTP ${r%% *})" test "${r%% *}" = 400
fi

# Validates one response against CONTRACT.md; $1 cluster id, $2 file, $3 host ids of the cluster, $4 datastore ids
CONTRACT_JQ='
  def sev: {"ok":0, "unknown":1, "degraded":2, "down":3}[.] // 1;
  def worst: if length == 0 then null else max_by(sev) end;
  . as $r
  | ($r.clusterId == $cid)
  and ($r.intervalSeconds | type) == "number"
  and ($r.lastRunAt | type) == "string"
  and ([$r.datastores[].name] == ([$r.datastores[].name] | sort))
  and ($r.state == (([$r.datastores[].state] + (if ($r.orphanMounts | length) > 0 then ["degraded"] else [] end)) | worst))
  and all($r.orphanMounts[]; (.hostId as $h | $hostids | index($h)) != null and (.since | type) == "string")
  and all($r.datastores[];
        (.id as $d | $dsids | index($d)) != null
        and (.counts | keys == ["degraded", "down", "ok", "unknown"])
        and ((.counts | add) == (.hosts | length))
        and (.hosts | length) == ($hostids | length)
        and ([.hosts[].hostId] == ($hostids | sort))
        and (.state == ([.hosts[].state] | worst))
        and all(.hosts[]; (.state | sev) >= 0 and (.state | IN("ok", "degraded", "down", "unknown"))
              and ((.name | type) == "string")
              and (if .activePaths == null then .state == "unknown" else
                    (.activePaths | type) == "number" and .activePaths <= .totalPaths and .totalPaths <= .expectedPaths end)
              and (.mounted | type | IN("boolean", "null")) and (.withdrawn | type | IN("boolean", "null"))
              and (if .mounted == null or .withdrawn == null then .state != "ok" else true end)
              and (if .withdrawn == true then .state == "down" else true end)))'

for cid in $CLUSTERS; do
  api GET "/api/clusters/$cid" >/dev/null
  hostids=$(jq -c '[.cluster.servers[]?.id]' "$TMP/body" 2>/dev/null) || hostids='[]'
  api GET "/api/clusters/$cid/datastores?max=1000" >/dev/null
  dsids=$(jq -c '[.datastores[]?.id]' "$TMP/body" 2>/dev/null) || dsids='[]'
  # the new instance starts with an empty result; wait for its first collection run (starts ~30 s after loading,
  # which also covers a small clock difference to the appliance)
  fresh='.lastRunAt != null and .lastRunAt >= $t'
  deadline=$((SECONDS + RUN_TIMEOUT)) r=""
  while ((SECONDS < deadline)); do
    r=$(route "clusterId=$cid")
    [[ ${r%% *} == 200 ]] && jq -e --arg t "$UPLOAD_AT" "$fresh" "$TMP/page" >/dev/null 2>&1 && break
    sleep 5
  done
  check "cluster $cid: JSON 200 with a collection run of the new version (HTTP ${r%% *})" \
    jq -e --arg t "$UPLOAD_AT" "$fresh" "$TMP/page"
  cp "$TMP/page" "$TMP/paths-$cid"
  check "cluster $cid: response matches CONTRACT.md" \
    jq -e --argjson cid "$cid" --argjson hostids "$hostids" --argjson dsids "$dsids" "$CONTRACT_JQ" "$TMP/paths-$cid"
  check "cluster $cid: intervalSeconds follows the setting ($TEST_INTERVAL)" \
    jq -e --argjson i "$TEST_INTERVAL" '.intervalSeconds == $i' "$TMP/paths-$cid"
  # informational output; the response may be no valid answer at all if the checks above failed
  summary=$(jq -r '.datastores[] | "\(.name): \(.state) \(.counts | to_entries | map("\(.key)=\(.value)") | join(" "))"' \
    "$TMP/paths-$cid" 2>/dev/null) || summary=""
  while read -r line; do [[ -n $line ]] && info "$line"; done <<<"$summary"
  [[ $(jq '.datastores | length' "$TMP/paths-$cid" 2>/dev/null || echo 0) -gt 0 ]] \
    || warn "cluster $cid: no shared-LUN datastore in the answer"
  errors=$(jq -r '[.datastores[].hosts[] | select(.error != null) | "host \(.hostId): \(.error)"] | unique | join("; ")' \
    "$TMP/paths-$cid" 2>/dev/null) || errors=""
  [[ -z $errors ]] || warn "cluster $cid: measurement errors: $errors"
  if [[ ${SPM_TEST_REQUIRE_OK:-0} == 1 ]]; then
    check "cluster $cid: all datastores ok (SPM_TEST_REQUIRE_OK)" jq -e 'all(.datastores[]; .state == "ok")' "$TMP/paths-$cid"
  fi
  route "clusterId=$cid&hosts=false" >/dev/null
  check "cluster $cid: hosts=false leaves out the host list" jq -e 'all(.datastores[]; has("hosts") | not)' "$TMP/page"
done

if [[ -n $CLUSTERS ]]; then
  cid=${CLUSTERS%% *}
  first_run=$(jq -r '.lastRunAt // empty' "$TMP/paths-$cid" 2>/dev/null) || first_run=""
  deadline=$((SECONDS + TEST_INTERVAL + 120)) later=$first_run
  while ((SECONDS < deadline)); do
    route "clusterId=$cid&hosts=false" >/dev/null
    later=$(jq -r '.lastRunAt // empty' "$TMP/page" 2>/dev/null) || later=""
    [[ -z $first_run ]] && first_run=$later && later=""
    [[ -n $later && $later != "$first_run" ]] && break
    sleep 5
  done
  check "background collection runs again after the interval ($first_run → $later)" test "$later" != "$first_run"

  r=$(ui_get "/infrastructure/clusters/$cid")
  check "cluster page offers the Multipath Status view" grep -q 'storage-path-monitor-tab' "$TMP/page"
fi

# --- 7. disable / enable --------------------------------------------------------------------------------------------

step "Disable and enable"
# without an HVM cluster an unknown id is used: the plugin answers it with JSON, Morpheus without the plugin with HTML
probe=${CLUSTERS%% *}; probe=${probe:-2147483647}
plugin_answers() { route "clusterId=$probe" >/dev/null; jq -e 'type == "object"' "$TMP/page" >/dev/null 2>&1; }
plugin_gone() { ! plugin_answers; }
check "plugin disabled" plugin_put "$PLUGIN_ID" '{"enabled":false}'
sleep 3
check "route gone while disabled" plugin_gone
check "plugin enabled" plugin_put "$PLUGIN_ID" '{"enabled":true}'
deadline=$((SECONDS + 60))
while ((SECONDS < deadline)) && ! plugin_answers; do sleep 3; done
check "route back after enabling" plugin_answers

# --- 8. permissions -------------------------------------------------------------------------------------------------

if [[ -n $CLUSTERS ]]; then
  step "Permissions"
  cid=${CLUSTERS%% *}
  # without Compute: host ids and states, but no host names
  # relogin <permission changes…> – changes the test role and signs in again (permissions are read at sign-in)
  relogin() {
    local change
    for change in "$@"; do set_perm "${change%%=*}" "${change#*=}" || return 1; done
    ui_login
  }
  check "role changed: Compute none" relogin admin-servers=none
  route "clusterId=$cid" >/dev/null
  check "without Compute read: host names hidden" \
    jq -e '(.datastores | length) > 0 and all(.datastores[].hosts[]; .name == null and (.hostId | type) == "number")
           and all(.orphanMounts[]; .name == null and .device == null and .mountPoint == null)' "$TMP/page"
  # without Storage (set to none): the plugin answers 403, the cluster page has no view
  check "role changed: Storage none" relogin infrastructure-storage=none
  r=$(route "clusterId=$cid")
  check "without Storage read: 403 JSON from the plugin (HTTP ${r%% *})" \
    bash -c '[[ $1 == 403 ]] && jq -e ".error == \"forbidden\"" "$2" >/dev/null' _ "${r%% *}" "$TMP/page"
  ui_get "/infrastructure/clusters/$cid" >/dev/null
  check "without Storage read: no Multipath Status view on the cluster page" bash -c '! grep -q storage-path-monitor-tab "$1"' _ "$TMP/page"
  # without Clusters: the plugin itself answers 403
  check "role changed: Storage read, Clusters none" relogin infrastructure-storage=read infrastructure-cluster=none
  r=$(route "clusterId=$cid")
  check "without Clusters read: 403 JSON from the plugin (HTTP ${r%% *})" \
    bash -c '[[ $1 == 403 ]] && jq -e ".error == \"forbidden\"" "$2" >/dev/null' _ "${r%% *}" "$TMP/page"
  ui_logout
fi

# restore and summary run in on_exit
