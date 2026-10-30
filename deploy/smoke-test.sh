#!/usr/bin/env bash
# End-to-end check of the demo stack. Runs in CI and works locally:
#   1. starts OpsPilot, its dashboard, Postgres, Prometheus, Alertmanager, Grafana and payment-api
#   2. stops payment-api: Prometheus fires ServiceDown, Alertmanager forwards it, OpsPilot opens an incident
#      and pushes it to a live update stream (read through the dashboard's proxy, as a browser would)
#   3. checks that escalation pages the on-call engineer
#   4. starts payment-api again: the alert resolves and OpsPilot resolves the incident automatically
#   5. checks sign-in sessions: the refresh-token cookie, rotation, reuse detection, CSRF refusal, sign-out
# It uses its own Compose project, so it never touches your local development database.
# Set KEEP=1 to leave the stack running afterwards.
set -euo pipefail
cd "$(dirname "$0")/.."

API=http://localhost:8080/api/v1
UI=http://localhost:4200

compose() {
  docker compose --project-name opspilot-smoke --profile demo "$@"
}

STREAM_LOG=$(mktemp)
STREAM_PID=
SESSION_DIR=$(mktemp -d) # cookie jars and response headers for the session checks

finish() {
  local status=$?
  if [ -n "$STREAM_PID" ]; then
    kill "$STREAM_PID" 2>/dev/null || true
  fi
  if [ "$status" -ne 0 ]; then
    echo "--- smoke test failed; recent logs ---"
    compose logs --tail=100 opspilot ui alertmanager prometheus || true
    echo "--- live update stream ---"
    cat "$STREAM_LOG" || true
  fi
  rm -rf "$STREAM_LOG" "$SESSION_DIR"
  if [ "${KEEP:-0}" != 1 ]; then
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap finish EXIT

# Retries a check every 2 seconds until it passes or the timeout (in seconds) runs out.
wait_for() {
  local description=$1 timeout=$2
  shift 2
  local deadline=$((SECONDS + timeout))
  until "$@" >/dev/null 2>&1; do
    if [ "$SECONDS" -ge "$deadline" ]; then
      echo "FAILED: $description (after ${timeout}s)"
      return 1
    fi
    sleep 2
  done
  echo "ok: $description"
}

# Runs a check once.
check() {
  local description=$1
  shift
  if ! "$@" >/dev/null 2>&1; then
    echo "FAILED: $description"
    return 1
  fi
  echo "ok: $description"
}

healthy() {
  curl -fs http://localhost:8081/actuator/health | jq -e '.status == "UP"'
}

payment_api_scraped() {
  curl -fsG http://localhost:9090/api/v1/query --data-urlencode 'query=up{job="payment-api"}' \
    | jq -e '.data.result[0].value[1] == "1"'
}

dashboard_provisioned() {
  curl -fs 'http://localhost:3000/api/search?query=OpsPilot' | jq -e 'length > 0'
}

# Deep links fall back to the app, and the security policy header is sent
ui_served() {
  curl -fs "$UI/incidents/any-id" | grep '<app-root' >/dev/null \
    && curl -fsI "$UI/" | grep -i '^content-security-policy:' >/dev/null
}

latest_incident_is() {
  curl -fs "$API/incidents?limit=1" -H "Authorization: Bearer $TOKEN" | jq -e ".items[0].status == \"$1\""
}

escalated() {
  curl -fs "$API/incidents/$INCIDENT_ID/timeline" -H "Authorization: Bearer $TOKEN" \
    | jq -e 'any(.[]; .type == "ESCALATED")'
}

metrics_exported() {
  curl -fs http://localhost:8081/actuator/prometheus | grep -q '^opspilot_alerts_received_total'
}

# status_is <expected HTTP status> <curl arguments...>
status_is() {
  local expected=$1
  shift
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$@")" = "$expected" ]
}

# The session cookie's value in a curl cookie jar; empty once deleted
session_cookie() {
  awk '$6 == "opspilot_session" { print $7 }' "$1"
}

sign_in_with_cookies() {
  curl -fs -c "$1" -D "$SESSION_DIR/headers" -o /dev/null -X POST "$UI/api/v1/auth/login" \
    -H 'Content-Type: application/json' -d '{"email":"admin@demo.local","password":"opspilot-dev-password"}'
}

# Sessions through the dashboard's proxy, as a browser has them (see docs/adr/0006-sessions.md)
check_sessions() {
  local jar=$SESSION_DIR/jar first second third token
  local csrf=(-H 'X-Requested-With: smoke-test')

  sign_in_with_cookies "$jar"
  check "sign-in sets an HttpOnly, SameSite=Strict session cookie for the auth API only" \
    grep -iE '^set-cookie: opspilot_session=[^;]+;.*Path=/api/v1/auth;.*HttpOnly;.*SameSite=Strict' \
    "$SESSION_DIR/headers"
  first=$(session_cookie "$jar")

  token=$(curl -fs -b "$jar" -c "$jar" "${csrf[@]}" -X POST "$UI/api/v1/auth/refresh" | jq -r .accessToken) \
    || { echo "FAILED: refresh with the session cookie"; return 1; }
  check "refresh returns an access token that works" \
    curl -fs "$UI/api/v1/me" -H "Authorization: Bearer $token"
  second=$(session_cookie "$jar")
  check "refresh rotates the session cookie" test -n "$second" -a "$second" != "$first"
  check "refresh without the CSRF header is refused" \
    status_is 403 -b "$jar" -X POST "$UI/api/v1/auth/refresh"

  echo "Waiting out the reuse grace period..."
  sleep 11
  check "a refresh token used twice is rejected" \
    status_is 401 -b "opspilot_session=$first" "${csrf[@]}" -X POST "$UI/api/v1/auth/refresh"
  check "and that ends the whole session" \
    status_is 401 -b "opspilot_session=$second" "${csrf[@]}" -X POST "$UI/api/v1/auth/refresh"

  sign_in_with_cookies "$jar"
  third=$(session_cookie "$jar")
  check "sign-out succeeds" status_is 204 -b "$jar" -c "$jar" "${csrf[@]}" -X POST "$UI/api/v1/auth/logout"
  check "sign-out deletes the session cookie" test -z "$(session_cookie "$jar")"
  check "a signed-out session cannot be refreshed" \
    status_is 401 -b "opspilot_session=$third" "${csrf[@]}" -X POST "$UI/api/v1/auth/refresh"
}

# The investigation assistant is on exactly when an Anthropic API key was given (in CI it is not)
assistant_state_matches_key() {
  local expected=false
  [ -n "${ANTHROPIC_API_KEY:-}" ] && expected=true
  curl -fs "$UI/api/v1/assistant" -H "Authorization: Bearer $TOKEN" | jq -e ".enabled == $expected"
}

compose up -d --build
wait_for "OpsPilot is healthy" 300 healthy
wait_for "Prometheus is scraping payment-api" 60 payment_api_scraped
wait_for "Grafana dashboard is provisioned" 60 dashboard_provisioned
wait_for "OpsPilot dashboard is served" 60 ui_served

TOKEN=$(curl -fs -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"admin@demo.local","password":"opspilot-dev-password"}' | jq -r .accessToken)
wait_for "investigation assistant is on only with an API key" 10 assistant_state_matches_key
check_sessions

curl -fsN "$UI/api/v1/incidents/stream" -H "Authorization: Bearer $TOKEN" > "$STREAM_LOG" &
STREAM_PID=$!
wait_for "live update stream connected" 30 grep -q '^event:ready' "$STREAM_LOG"

echo "Breaking payment-api..."
compose stop payment-api
wait_for "incident opened from the Prometheus alert" 120 latest_incident_is TRIGGERED
wait_for "incident pushed to the live update stream" 10 grep -q '"type":"INCIDENT_CREATED"' "$STREAM_LOG"
INCIDENT_ID=$(curl -fs "$API/incidents?limit=1" -H "Authorization: Bearer $TOKEN" | jq -r '.items[0].id')
wait_for "on-call engineer paged" 60 escalated

echo "Fixing payment-api..."
compose start payment-api
wait_for "incident resolved automatically when the alert cleared" 120 latest_incident_is RESOLVED
wait_for "OpsPilot metrics exported" 30 metrics_exported

echo "Demo smoke test passed."
