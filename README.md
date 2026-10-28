# OpsPilot

AI-assisted incident response platform for small engineering teams.

## Demo: watch a real outage flow through
One command starts OpsPilot with Postgres, Prometheus, Alertmanager, Grafana and a sample `payment-api` service:
```
docker compose --profile demo up -d --build
```
| What | Where |
|---|---|
| OpsPilot API | http://localhost:8080 (login `admin@demo.local` / `opspilot-dev-password`) |
| Grafana dashboard | http://localhost:3000 (opens on the OpsPilot dashboard; admin login `admin` / `admin`) |
| Prometheus / Alertmanager | http://localhost:9090 / http://localhost:9093 |

Break the service and watch what happens:
```
docker compose --profile demo stop payment-api    # simulate an outage
```
1. Within about 20 seconds Prometheus fires `ServiceDown`, and Alertmanager sends it to OpsPilot.
2. OpsPilot opens a CRITICAL incident for `payment-api`. The Payments team's escalation policy pages the on-call admin; the page appears in `docker compose logs opspilot` (no Slack webhook is configured in the demo).
3. `curl localhost:8080/api/v1/incidents -H "Authorization: Bearer $TOKEN"` shows the incident and its timeline (see "Try it" below for getting a token).
```
docker compose --profile demo start payment-api   # recover
```
4. The alert clears, Alertmanager reports it resolved, and OpsPilot resolves the incident automatically.

`deploy/smoke-test.sh` runs exactly this scenario and checks every step; CI runs it on every push.

## Run locally (development)
```
docker compose up -d                                      # Postgres only
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
The `dev` profile seeds a demo tenant:
- API key for webhooks: `opspilot-dev-key`
- Admin login: `admin@demo.local` / `opspilot-dev-password`
- A Payments team that owns `payment-api` and pages the admin

Health: http://localhost:8081/actuator/health (actuator runs on a separate, internal port).

Outside the `dev` profile, set `JWT_SECRET` (base64, at least 256 bits: `openssl rand -base64 32`).

## Try it
```
# A monitoring system sends an alert (repeat it: the second call returns DUPLICATE)
curl -X POST localhost:8080/api/v1/webhooks/generic \
  -H "X-API-Key: opspilot-dev-key" -H "Content-Type: application/json" \
  -d '{"eventId":"evt-1","service":"payment-api","alertName":"HighLatency","severity":"CRITICAL"}'

# An engineer logs in and works the incident
TOKEN=$(curl -s -X POST localhost:8080/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"email":"admin@demo.local","password":"opspilot-dev-password"}' | jq -r .accessToken)

curl localhost:8080/api/v1/incidents -H "Authorization: Bearer $TOKEN"
curl -X POST localhost:8080/api/v1/incidents/{id}/acknowledge -H "Authorization: Bearer $TOKEN"
curl -X POST localhost:8080/api/v1/incidents/{id}/comments -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"body":"Rolling back the last deploy"}'
curl -X POST localhost:8080/api/v1/incidents/{id}/resolve -H "Authorization: Bearer $TOKEN"
curl localhost:8080/api/v1/incidents/{id}/timeline -H "Authorization: Bearer $TOKEN"
```

## Connect Prometheus Alertmanager
Create an API key (`POST /api/v1/api-keys` as an admin), then add a receiver to `alertmanager.yml`:
```yaml
route:
  receiver: opspilot
receivers:
  - name: opspilot
    webhook_configs:
      - url: https://<your-opspilot-host>/api/v1/webhooks/alertmanager
        send_resolved: true
        http_config:
          authorization:
            type: Bearer
            credentials: <api key>
```
How alerts are mapped:
- **Service:** the `service` label, or the Prometheus `job` if there is none.
- **Severity:** the `severity` label: `critical`/`page` → CRITICAL, `high`/`error` → HIGH, `warning` → MEDIUM, `info`/`low`/`none` → LOW.
- **Summary:** the `summary` annotation, or `description`.
- **Grouping:** one incident per service and alert name, so the same alert firing on three pods is one incident with three alerts.
- **Repeats:** Alertmanager re-sends firing alerts on every `repeat_interval`; these are recognised (by fingerprint and start time) and ignored.
- **Resolution:** a resolved alert is recorded on the incident's timeline. When the incident's last firing alert resolves, the incident is resolved automatically. Add `?autoResolve=false` to the URL to keep incidents open until an engineer resolves them.

## API
| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/webhooks/{source}` | API key | Generic format. 201 new incident, 200 grouped or duplicate |
| POST | `/api/v1/webhooks/alertmanager?autoResolve=` | API key | Prometheus Alertmanager format; one result per alert |
| POST | `/api/v1/auth/signup` | none | Creates a tenant and its first admin |
| POST | `/api/v1/auth/login` | none | Returns a 1-hour JWT |
| GET | `/api/v1/me` | JWT | |
| GET, POST | `/api/v1/users` | JWT (POST: admin) | |
| GET, POST | `/api/v1/teams` | JWT (POST: admin) | |
| PUT, DELETE | `/api/v1/teams/{id}/members/{userId}` | JWT admin | |
| PUT, DELETE | `/api/v1/teams/{id}/slack-webhook` | JWT admin | `{"url": "https://hooks.slack.com/services/..."}` |
| GET, PUT | `/api/v1/teams/{id}/escalation-policy` | JWT (PUT: admin) | See below |
| GET | `/api/v1/services` | JWT | Registered automatically by incoming alerts |
| PUT | `/api/v1/services/{id}/team` | JWT admin | The owning team's policy escalates the service's incidents |
| GET, POST, DELETE | `/api/v1/api-keys` | JWT admin | The raw key is shown only once, on create |
| GET | `/api/v1/incidents?status=&limit=` | JWT | Newest first |
| GET | `/api/v1/incidents/{id}` | JWT | |
| GET | `/api/v1/incidents/{id}/timeline` | JWT | Who did what, when |
| POST | `/api/v1/incidents/{id}/acknowledge` | JWT | Assigns the incident to you if unassigned |
| POST | `/api/v1/incidents/{id}/assign` | JWT | `{"assigneeId": "..."}` |
| POST | `/api/v1/incidents/{id}/comments` | JWT | `{"body": "..."}` |
| POST | `/api/v1/incidents/{id}/resolve` | JWT | |

All data is scoped to the caller's tenant. State changes return 409 on an invalid transition or a concurrent update.

## Escalation
```json
PUT /api/v1/teams/{id}/escalation-policy
{"steps": [
  {"userIds": ["<on-call engineer>"], "escalateAfterMinutes": 5},
  {"userIds": ["<team lead>", "<backup>"], "escalateAfterMinutes": 10}
]}
```
When an incident opens on a service owned by the team, level 1 is paged immediately. If nobody acknowledges within 5 minutes, level 2 is paged, and so on. Acknowledging or resolving stops escalation. Pages go to the team's Slack webhook, or to the application log if none is configured. Every level paged appears in the incident timeline as `ESCALATED`.

## How reliability is enforced
- **Idempotent ingestion:** unique `(tenant_id, source, source_event_id)` on `alert` + `ON CONFLICT DO NOTHING`. A retry returns the original result.
- **Auto-resolve without write skew:** when alerts clear, the "is anything still firing?" check runs under the incident's row lock, so two alerts clearing at once cannot leave an incident open with nothing firing.
- **Grouping:** a partial unique index allows one open incident per fingerprint; ingestion upserts into it, so concurrent alerts cannot open duplicate incidents.
- **Lifecycle:** state machine `TRIGGERED → ACKNOWLEDGED → RESOLVED` plus `@Version` optimistic locking; concurrent transitions get a 409.
- **Authentication:** API keys for machines, JWTs for people, on separate filter chains (see `docs/adr/0002-authentication.md`).
- **Transactional outbox:** incident events are written in the same transaction as the change, then relayed in order per incident, with retries and a parked `FAILED` state.
- **Escalation:** the schedule lives in Postgres (survives restarts); workers claim rows with `FOR UPDATE SKIP LOCKED` (safe on many instances) and re-check the incident before paging. Pages are deduplicated per incident, level and person. See `docs/adr/0003-outbox-and-escalation.md`.

## Observability
Prometheus metrics are served at `http://<host>:8081/actuator/prometheus`. Port 8081 is for internal use only and must not be exposed publicly.

| Metric | Meaning |
|---|---|
| `opspilot_alerts_received_total{integration, outcome}` | Every alert event and what happened to it, including duplicates suppressed |
| `opspilot_incidents_opened_total{severity}` | Incidents opened |
| `opspilot_incidents_open{status}` | Incidents waiting for acknowledgement / being worked on |
| `opspilot_incidents_time_to_acknowledge_seconds` | Histogram: time from opening to acknowledgement |
| `opspilot_incidents_time_to_resolve_seconds{resolution}` | Histogram, manual vs automatic resolution |
| `opspilot_escalations_paged_total{level}`, `opspilot_escalations_active` | Escalation activity |
| `opspilot_outbox_pending`, `_failed`, `_oldest_pending_age_seconds` | Event relay backlog and lag |
| `opspilot_notifications_sent_total{channel}`, `_failures_total`, `_pending`, `_failed` | Notification delivery |

Counters are recorded only after their transaction commits. Backlog gauges are computed from the database, so they are correct with several instances running.

Every response carries an `X-Request-Id` header, taken from the request if the caller sent one, otherwise generated. It appears on every log line of that request and in every error body (`"requestId"`), so a customer's error report leads straight to the matching logs.

## Tests
Integration tests use Testcontainers and need Docker: `./mvnw verify`.
The end-to-end demo check needs Docker Compose and `jq`: `bash deploy/smoke-test.sh`.

## Structure
Modular monolith under `com.opspilot.*`; see `docs/adr`.
