#!/usr/bin/env bash
# End-to-end check of the demo stack. Runs in CI and works locally:
#   1. starts OpsPilot, Postgres, Prometheus, Alertmanager, Grafana and payment-api
#   2. stops payment-api: Prometheus fires ServiceDown, Alertmanager forwards it, OpsPilot opens an incident
#   3. checks that escalation pages the on-call engineer
#   4. starts payment-api again: the alert resolves and OpsPilot resolves the incident automatically
# It uses its own Compose project, so it never touches your local development database.
# Set KEEP=1 to leave the stack running afterwards.
set -euo pipefail
cd "$(dirname "$0")/.."

API=http://localhost:8080/api/v1

compose() {
  docker compose --project-name opspilot-smoke --profile demo "$@"
}

finish() {
  local status=$?
  if [ "$status" -ne 0 ]; then
    echo "--- smoke test failed; recent logs ---"
    compose logs --tail=100 opspilot alertmanager prometheus || true
  fi
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

compose up -d --build
wait_for "OpsPilot is healthy" 300 healthy
wait_for "Prometheus is scraping payment-api" 60 payment_api_scraped
wait_for "Grafana dashboard is provisioned" 60 dashboard_provisioned

TOKEN=$(curl -fs -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"admin@demo.local","password":"opspilot-dev-password"}' | jq -r .accessToken)

echo "Breaking payment-api..."
compose stop payment-api
wait_for "incident opened from the Prometheus alert" 120 latest_incident_is TRIGGERED
INCIDENT_ID=$(curl -fs "$API/incidents?limit=1" -H "Authorization: Bearer $TOKEN" | jq -r '.items[0].id')
wait_for "on-call engineer paged" 60 escalated

echo "Fixing payment-api..."
compose start payment-api
wait_for "incident resolved automatically when the alert cleared" 120 latest_incident_is RESOLVED
wait_for "OpsPilot metrics exported" 30 metrics_exported

echo "Demo smoke test passed."
