#!/usr/bin/env bash
# AI-Proxy smoke tests.
# Runs inside a one-shot container on the compose network against
# gateway:8080 and control-plane:8081. Writes results (log + JUnit XML)
# into $RESULTS_DIR (bind-mounted to deploy/test-results on the host).
# Exit code 0 = all passed, 1 = at least one failure.

set -u

GATEWAY_URL=${GATEWAY_URL:-http://gateway:8080}
CONTROL_PLANE_URL=${CONTROL_PLANE_URL:-http://control-plane:8081}
DAILY_LIMIT=${DAILY_LIMIT:-20}
ADMIN_EMAIL=${ADMIN_EMAIL:-admin@example.com}
ADMIN_PASS=${ADMIN_PASS:-admin-pass-123}
RESULTS_DIR=${RESULTS_DIR:-/results}

MODEL_BODY='{"model":"gpt-4o-mini","messages":[{"role":"user","content":"ping"}]}'

PASS=0
FAIL=0
CURRENT="init"
USER_JAR=$(mktemp) || USER_JAR=/tmp/user.jar
ADMIN_JAR=$(mktemp) || ADMIN_JAR=/tmp/admin.jar
declare -a log_lines=()
declare -a results=()

step() {
  CURRENT="$1"
  echo
  echo "RUN  $1"
  log_lines+=("RUN  $1")
}

ok() {
  PASS=$((PASS + 1))
  echo "PASS  $CURRENT"
  log_lines+=("PASS  $CURRENT")
  results+=("$CURRENT"$'\t'"PASS"$'\t')
}

ko() {
  FAIL=$((FAIL + 1))
  echo "FAIL  $CURRENT :: $1"
  log_lines+=("FAIL  $CURRENT :: $1")
  results+=("$CURRENT"$'\t'"FAIL"$'\t'"$1")
}

user_json() { jq -nc --arg e "$1" --arg p "$2" '{email:$e,password:$p}'; }

http_status() {
  local method="$1" url="$2" jar="${3:-}" data="${4:-}"
  local args=(-s -o /dev/null -w '%{http_code}' -X "$method")
  [ -n "$jar" ] && args+=(-b "$jar" -c "$jar")
  [ -n "$data" ] && args+=(-H 'Content-Type: application/json' --data-raw "$data")
  curl "${args[@]}" "$url"
}

http_json() {
  local method="$1" url="$2" jar="${3:-}" data="${4:-}"
  local args=(-s -X "$method")
  [ -n "$jar" ] && args+=(-b "$jar" -c "$jar")
  [ -n "$data" ] && args+=(-H 'Content-Type: application/json' --data-raw "$data")
  curl "${args[@]}" "$url"
}

model_call() {
  curl -s -o /dev/null -w '%{http_code}' -X POST \
    -H 'Content-Type: application/json' \
    -H "X-Gateway-Key: $GATEWAY_KEY" \
    --data-raw "$MODEL_BODY" \
    "$GATEWAY_URL/openai/v1/chat/completions"
}

wait_healthy() {
  local i=0 g c
  while [ "$i" -lt 90 ]; do
    g=$(http_status GET "$GATEWAY_URL/actuator/health")
    c=$(http_status GET "$CONTROL_PLANE_URL/actuator/health")
    if [ "$g" = "200" ] && [ "$c" = "200" ]; then return 0; fi
    i=$((i + 1))
    sleep 2
  done
  return 1
}

write_results() {
  mkdir -p "$RESULTS_DIR"
  local log="$RESULTS_DIR/results.log"
  local xml="$RESULTS_DIR/results.xml"
  {
    echo "AI-Proxy smoke test"
    echo "PASS=$PASS FAIL=$FAIL"
    for l in "${log_lines[@]}"; do echo "$l"; done
  } > "$log"

  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo "<testsuite name=\"ai-proxy-smoke\" tests=\"$((PASS + FAIL))\" failures=\"$FAIL\">"
    local name st msg
    for r in "${results[@]}"; do
      IFS=$'\t' read -r name st msg <<< "$r"
      if [ "$st" = "PASS" ]; then
        echo "  <testcase name=\"$name\"/>"
      else
        echo "  <testcase name=\"$name\"><failure message=\"$msg\"/></testcase>"
      fi
    done
    echo "</testsuite>"
  } > "$xml"
  echo
  echo "Results written to $log and $xml"
}

trap 'rm -f "$USER_JAR" "$ADMIN_JAR"' EXIT

# ------------------------------------------------------------------ steps

step "wait for gateway + control-plane health"
if wait_healthy; then ok; else ko "services not healthy after 180s"; fi

step "register admin (idempotent)"
reg=$(http_status POST "$CONTROL_PLANE_URL/api/auth/register" "" "$(user_json "$ADMIN_EMAIL" "$ADMIN_PASS")")
if [ "$reg" = "201" ] || [ "$reg" = "409" ]; then ok; else ko "admin register failed: $reg"; fi

step "register unique smoke user"
USER_EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
USER_PASS="smoke-pass-123"
reg=$(http_status POST "$CONTROL_PLANE_URL/api/auth/register" "" "$(user_json "$USER_EMAIL" "$USER_PASS")")
if [ "$reg" = "201" ]; then ok; else ko "user register failed: $reg"; fi

step "login as user"
login=$(http_json POST "$CONTROL_PLANE_URL/api/auth/login" "$USER_JAR" "$(user_json "$USER_EMAIL" "$USER_PASS")")
USER_ID=$(printf '%s' "$login" | jq -r '.id // empty')
if [ -n "$USER_ID" ]; then ok; else ko "user login failed"; fi

step "create gateway key"
keyresp=$(http_json POST "$CONTROL_PLANE_URL/api/keys" "$USER_JAR")
GATEWAY_KEY=$(printf '%s' "$keyresp" | jq -r '.plaintext // empty')
if [ -n "$GATEWAY_KEY" ]; then ok; else ko "create key failed"; fi
echo "      key=${GATEWAY_KEY:0:16}..., user=$USER_ID"

step "negative checks: no key (403), bad key (401), unknown provider (404)"
noKey=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  --data-raw "$MODEL_BODY" "$GATEWAY_URL/openai/v1/chat/completions")
badKey=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
  -H 'X-Gateway-Key: wrong-key' --data-raw "$MODEL_BODY" "$GATEWAY_URL/openai/v1/chat/completions")
unknown=$(curl -s -o /dev/null -w '%{http_code}' -H "X-Gateway-Key: $GATEWAY_KEY" \
  "$GATEWAY_URL/nosuch/v1/x")
if [ "$noKey" = "403" ] && [ "$badKey" = "401" ] && [ "$unknown" = "404" ]; then
  ok
else
  ko "expected 403/401/404, got $noKey/$badKey/$unknown"
fi

step "quota: expect 429 exactly on call $((DAILY_LIMIT + 1))"
attempts=0
hit429="no"
for ((i = 1; i <= DAILY_LIMIT + 5; i++)); do
  attempts=$i
  code=$(model_call)
  if [ "$code" = "429" ]; then hit429="yes"; break; fi
  sleep 0.25
done
if [ "$hit429" = "yes" ] && [ "$attempts" -eq $((DAILY_LIMIT + 1)) ]; then
  ok
  echo "      got 429 on call $attempts as expected"
else
  ko "expected 429 on call $((DAILY_LIMIT + 1)), got it on $attempts (last=$code)"
fi

step "admin login"
adminLogin=$(http_json POST "$CONTROL_PLANE_URL/api/auth/login" "$ADMIN_JAR" "$(user_json "$ADMIN_EMAIL" "$ADMIN_PASS")")
if printf '%s' "$adminLogin" | jq -e '.id' >/dev/null 2>&1; then ok; else ko "admin login failed"; fi

step "generate PRO code"
codes=$(http_json POST "$CONTROL_PLANE_URL/api/admin/codes" "$ADMIN_JAR" '{"count":1,"tier":"PRO"}')
PRO_CODE=$(printf '%s' "$codes" | jq -r '.codes[0] // empty')
if [ -n "$PRO_CODE" ]; then ok; else ko "generate PRO code failed"; fi

step "activate PRO code"
act=$(http_json POST "$CONTROL_PLANE_URL/api/subscriptions/activate" "$USER_JAR" "$(jq -nc --arg c "$PRO_CODE" '{code:$c}')")
tier=$(printf '%s' "$act" | jq -r '.tier // empty')
if [ "$tier" = "PRO" ]; then ok; else ko "activation failed, tier=$tier"; fi

step "PRO user is unlimited (usage shows null limit)"
usage=$(http_json GET "$CONTROL_PLANE_URL/api/usage" "$USER_JAR")
dlimit=$(printf '%s' "$usage" | jq -r '.dailyLimit // empty')
if [ -z "$dlimit" ]; then ok; else ko "PRO usage should be unlimited, got dailyLimit=$dlimit"; fi

step "PRO user passes gateway after limits cache refresh"
proOk="no"
for ((i = 0; i < 80; i++)); do
  after=$(model_call)
  if [ "$after" != "429" ]; then proOk="yes"; break; fi
  sleep 1
done
if [ "$proOk" = "yes" ]; then ok; else ko "PRO user still got 429 after waiting for limits cache"; fi

step "prometheus metrics exposed"
gwMetrics=$(curl -s "$GATEWAY_URL/actuator/prometheus")
cpMetrics=$(curl -s "$CONTROL_PLANE_URL/actuator/prometheus")
if printf '%s' "$gwMetrics" | grep -q 'gateway_quota_exceeded_total' \
  && printf '%s' "$gwMetrics" | grep -q 'gateway_invalid_key_total' \
  && printf '%s' "$cpMetrics" | grep -q 'http_server_requests_seconds_count'; then
  ok
else
  ko "expected prometheus metrics not found"
fi

# ---------------------------------------------------------------- summary
echo
if [ "$FAIL" -eq 0 ]; then
  echo "ALL SMOKE TESTS PASSED (PASS=$PASS)"
else
  echo "SMOKE TESTS FAILED (PASS=$PASS FAIL=$FAIL)"
fi
write_results
[ "$FAIL" -eq 0 ]
