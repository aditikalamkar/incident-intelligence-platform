# OpsPilot

AI-assisted incident response platform for small engineering teams.

## Demo: watch a real outage flow through
One command starts OpsPilot and its dashboard with Postgres, Prometheus, Alertmanager, Grafana and a sample `payment-api` service:
```
docker compose --profile demo up -d --build
```
| What | Where |
|---|---|
| **OpsPilot dashboard** | http://localhost:4200 (sign in as `admin@demo.local` / `opspilot-dev-password`) |
| OpsPilot API | http://localhost:8080 |
| Grafana dashboard | http://localhost:3000 (opens on the OpsPilot dashboard; admin login `admin` / `admin`) |
| Prometheus / Alertmanager | http://localhost:9090 / http://localhost:9093 |

Open the dashboard, then break the service and watch what happens:
```
docker compose --profile demo stop payment-api    # simulate an outage
```
1. Within about 20 seconds Prometheus fires `ServiceDown`, and Alertmanager sends it to OpsPilot.
2. A CRITICAL incident for `payment-api` appears on the dashboard without reloading. The Payments team's escalation policy pages the on-call admin; the page appears in the incident's timeline and in `docker compose logs opspilot` (no Slack webhook is configured in the demo).
3. Open the incident: acknowledge it, assign it, add a comment. Open a second browser window: every change shows up there live too.
```
docker compose --profile demo start payment-api   # recover
```
4. The alert clears, Alertmanager reports it resolved, and OpsPilot resolves the incident automatically: it disappears from the "Open" view.

`deploy/smoke-test.sh` runs exactly this scenario and checks every step; CI runs it on every push.

With an Anthropic API key, the incident page also has the [investigation assistant](#investigation-assistant): run `export ANTHROPIC_API_KEY=<your key>` before `docker compose ... up`, then open an incident and click **Investigate**.

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

The dashboard (needs Node.js 24), with the backend above running:
```
cd frontend
npm install
npm start          # http://localhost:4200, forwards /api to localhost:8080
```

Outside the `dev` profile, set `JWT_SECRET` (base64, at least 256 bits: `openssl rand -base64 32`). Set `ANTHROPIC_API_KEY` to turn on the investigation assistant.

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
| POST | `/api/v1/auth/signup` | none | Creates a tenant and its first admin; signs in like login |
| POST | `/api/v1/auth/login` | none | Returns a 15-minute access token and sets the session cookie |
| POST | `/api/v1/auth/refresh` | session cookie | New access token; rotates the cookie. See "Sign-in sessions" |
| POST | `/api/v1/auth/logout` | session cookie | Ends the session and deletes the cookie |
| GET | `/api/v1/me` | JWT | |
| GET, POST | `/api/v1/users` | JWT (POST: admin) | |
| GET, POST | `/api/v1/teams` | JWT (POST: admin) | |
| PUT, DELETE | `/api/v1/teams/{id}/members/{userId}` | JWT admin | |
| PUT, DELETE | `/api/v1/teams/{id}/slack-webhook` | JWT admin | `{"url": "https://hooks.slack.com/services/..."}` |
| GET, PUT | `/api/v1/teams/{id}/escalation-policy` | JWT (PUT: admin) | See below |
| GET | `/api/v1/services` | JWT | Registered automatically by incoming alerts |
| PUT | `/api/v1/services/{id}/team` | JWT admin | The owning team's policy escalates the service's incidents |
| GET, POST, DELETE | `/api/v1/api-keys` | JWT admin | The raw key is shown only once, on create |
| GET | `/api/v1/incidents` | JWT | Search, newest first; see "Searching incidents" |
| GET | `/api/v1/incidents/stream` | JWT | Live updates (Server-Sent Events); see "Live updates" |
| GET | `/api/v1/incidents/{id}` | JWT | |
| GET | `/api/v1/incidents/{id}/timeline` | JWT | Who did what, when |
| POST | `/api/v1/incidents/{id}/acknowledge` | JWT | Assigns the incident to you if unassigned |
| POST | `/api/v1/incidents/{id}/assign` | JWT | `{"assigneeId": "..."}` |
| POST | `/api/v1/incidents/{id}/comments` | JWT | `{"body": "..."}` |
| POST | `/api/v1/incidents/{id}/resolve` | JWT | |
| GET | `/api/v1/assistant` | JWT | Whether the investigation assistant is on, and its model |
| POST | `/api/v1/incidents/{id}/investigations` | JWT | Starts an investigation (202), or returns the one running |
| GET | `/api/v1/incidents/{id}/investigations/latest` | JWT | 204 if the incident was never investigated |

All data is scoped to the caller's tenant. State changes return 409 on an invalid transition or a concurrent update.

## Sign-in sessions
Signing in returns a short-lived **access token** (15 minutes), sent as `Authorization: Bearer`. It also sets a **session cookie** holding a refresh token. The cookie is `HttpOnly`, `SameSite=Strict`, `Secure` outside the dev profile, and limited to `/api/v1/auth`.

The dashboard keeps the access token in memory only. It renews the token from the session a minute before it expires, and after a reload, so the session survives reloads without the token ever being readable by scripts.

```
curl -c jar -X POST localhost:8080/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"email":"admin@demo.local","password":"opspilot-dev-password"}'
curl -b jar -c jar -X POST localhost:8080/api/v1/auth/refresh -H "X-Requested-With: curl"   # new token, new cookie
curl -b jar -c jar -X POST localhost:8080/api/v1/auth/logout -H "X-Requested-With: curl"    # 204
```
- **Rotation:** every refresh replaces the cookie's token. A token used a second time means someone copied it, so the whole session ends (401) for the thief and the user alike. A repeat within 10 seconds is taken as the same browser refreshing twice at once, not as theft.
- **CSRF:** `refresh` and `logout` require an `X-Requested-With` header (any value), and refuse requests that browsers mark as cross-site (`Sec-Fetch-Site`).
- **Ending:** a session ends after 7 days without a refresh, 30 days after sign-in, on sign-out, or when the same browser signs in again. Refreshing picks up role changes.
- **Same origin:** the dashboard must be served from the same origin as the API (the bundled nginx proxies `/api`).

| Setting | Default | Meaning |
|---|---|---|
| `opspilot.security.jwt.ttl` | `PT15M` | Access token lifetime |
| `opspilot.security.session.idle-timeout` | `P7D` | A session not refreshed for this long ends |
| `opspilot.security.session.max-lifetime` | `P30D` | A session ends this long after sign-in, however active |
| `opspilot.security.session.reuse-grace-period` | `PT10S` | A repeated token this soon after rotation is a race, not theft; `PT0S` turns it off |
| `opspilot.security.session.secure-cookie` | `true` | `false` only for local HTTP (the `dev` profile) |

Design and trade-offs: `docs/adr/0006-sessions.md`.

## Searching incidents
`GET /api/v1/incidents` takes any combination of these filters (combined with AND):

| Parameter | Example | Meaning |
|---|---|---|
| `status` | `status=TRIGGERED,ACKNOWLEDGED` | One or more statuses |
| `severity` | `severity=CRITICAL&severity=HIGH` | One or more severities |
| `serviceId` | `serviceId=<id>` | One or more services |
| `teamId` | `teamId=<id>` | Services owned by the team |
| `assignee` | `assignee=me`, `assignee=none`, `assignee=<user id>` | Mine, unassigned, or someone's |
| `openedAfter`, `openedBefore` | `openedAfter=2026-10-01T00:00:00Z` | Date range (after is inclusive, before exclusive) |
| `q` | `q=connection pool` | Every word must appear in the title or a comment (case-insensitive) |
| `limit`, `cursor` | `limit=50&cursor=<nextCursor>` | Paging (max 200 per page) |

The response is `{"items": [...], "nextCursor": "..."}`; `nextCursor` is null on the last page. Paging uses a cursor (the last incident's open time and ID), not an offset, so pages never repeat or skip incidents while new ones are opening.

Example: my open critical incidents mentioning "timeout":
```
curl -G localhost:8080/api/v1/incidents -H "Authorization: Bearer $TOKEN" \
  --data-urlencode "assignee=me" --data-urlencode "status=TRIGGERED,ACKNOWLEDGED" \
  --data-urlencode "severity=CRITICAL" --data-urlencode "q=timeout"
```

## Live updates
`GET /api/v1/incidents/stream` keeps the connection open and pushes every change to any of your tenant's incidents as [Server-Sent Events](https://html.spec.whatwg.org/multipage/server-sent-events.html):
```
curl -N localhost:8080/api/v1/incidents/stream -H "Authorization: Bearer $TOKEN"
```
| Event | Data | What the client does |
|---|---|---|
| `ready` | `{}` | Connected: load the incident list now (anything that changes from here on is streamed) |
| `incident` | `{"incident": {...}, "event": {...}}` | The incident's current state and the timeline event that changed it (created, alert grouped, acknowledged, assigned, comment, escalated, resolved...) |
| `resync` | `{}` | Changes may have been missed: reload the list |

- Replace your copy of an incident only if the update's `version` and `alertCount` are not lower than yours: an update and a reload can arrive in either order.
- Lines starting with `:` are heartbeats (every 25 seconds); ignore them.
- The stream ends when your token expires, or when the server restarts. Reconnect with a valid token; you will get `ready` again.
- Authentication uses the `Authorization` header, which the browser `EventSource` API cannot send. Read the stream with `fetch` instead.

It works with any number of OpsPilot instances: changes are shared between them through PostgreSQL `LISTEN/NOTIFY`, sent only once the change is committed. See `docs/adr/0004-live-updates.md`.

## Investigation assistant
On an incident's page, **Investigate** asks Claude to read the incident and report back. Claude reads:
- the incident's alerts;
- its timeline, including comments;
- earlier incidents on the same service, with their last comments.

It returns:
- a summary of what is happening;
- findings and suggested next steps, each linked to the alerts, timeline events and earlier incidents it relies on.

A reference the model makes up is dropped, so every link leads to something real.

It only reads; it cannot change an incident. Results are stored and shared:
- When the result is ready, the incident's timeline gains an `INVESTIGATION_COMPLETED` event, so everyone viewing the incident sees it right away.
- Asking again while an investigation is running joins it, instead of paying for a second one.

Turn it on by setting `ANTHROPIC_API_KEY`. **Incident data, including comments and user names, is then sent to Anthropic's API.**

| Setting | Default | Meaning |
|---|---|---|
| `opspilot.ai.api-key` | `${ANTHROPIC_API_KEY}` | Empty turns the assistant off |
| `opspilot.ai.model` | `claude-opus-5-5` | |
| `opspilot.ai.effort` | `medium` | How much the model thinks before answering: `low` to `max` |
| `opspilot.ai.max-concurrent` | `4` | Investigations sent at once per instance; others wait their turn |
| `opspilot.ai.daily-limit-per-tenant` | `100` | Per organisation in any 24 hours; then 429 |

```
curl -X POST localhost:8080/api/v1/incidents/{id}/investigations -H "Authorization: Bearer $TOKEN"
curl localhost:8080/api/v1/incidents/{id}/investigations/latest -H "Authorization: Bearer $TOKEN"
```
```json
{"id": "...", "status": "COMPLETED", "model": "claude-opus-5-5", "requestedBy": "...",
 "result": {
   "summary": "payment-api has answered slowly since 14:05, right after a deploy ...",
   "findings": [{"text": "...", "sources": [{"kind": "EVENT", "id": "...", "label": "Comment by Alice Chen", "at": "..."}]}],
   "nextSteps": [{"text": "...", "sources": [{"kind": "INCIDENT", "id": "...", "label": "DiskFull on payment-api", "at": "..."}]}],
   "asOf": "..."}}
```
`status` is `RUNNING`, then `COMPLETED` or `FAILED` (with `failure` explaining why, in words for the user). `asOf` is when the incident was read: timeline events after it were not seen. Design and safeguards: `docs/adr/0005-investigation-assistant.md`.

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
- **Sessions:** refresh tokens rotate on every use and are stored only as hashes. A reused token revokes its session. Concurrent refreshes take turns on a row lock, so only one can rotate a token (see `docs/adr/0006-sessions.md`).
- **Transactional outbox:** incident events are written in the same transaction as the change, then relayed in order per incident, with retries and a parked `FAILED` state.
- **Live updates:** changes are announced with `NOTIFY` inside the changing transaction, so a browser never sees a change that was rolled back; streams are isolated per client, and clients reload whenever a change could have been missed (see `docs/adr/0004-live-updates.md`).
- **Escalation:** the schedule lives in Postgres (survives restarts); workers claim rows with `FOR UPDATE SKIP LOCKED` (safe on many instances) and re-check the incident before paging. Pages are deduplicated per incident, level and person. See `docs/adr/0003-outbox-and-escalation.md`.
- **Assistant:** a partial unique index allows one running investigation per incident, so simultaneous requests share it. The result and its timeline event are written in one transaction. Cited sources are checked against what the model was shown (see `docs/adr/0005-investigation-assistant.md`).

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
| `opspilot_incident_streams_open` | Live update connections on this instance |
| `opspilot_auth_refreshes_total{outcome}` | Session refreshes: `ROTATED`, `CONCURRENT`, `EXPIRED`, `REVOKED`, `UNKNOWN`, `MISSING`, and `REUSED` (a stolen token: alert on it) |
| `opspilot_ai_investigations_total{outcome}` | Assistant investigations completed or failed |
| `opspilot_ai_investigation_duration_seconds{outcome}` | Histogram: how long investigations take, waiting included |
| `opspilot_ai_tokens_total{type, model}` | Claude tokens used, input and output: what the assistant costs |

Counters are recorded only after their transaction commits. Backlog gauges are computed from the database, so they are correct with several instances running.

Every response carries an `X-Request-Id` header, taken from the request if the caller sent one, otherwise generated. It appears on every log line of that request and in every error body (`"requestId"`), so a customer's error report leads straight to the matching logs.

## Tests
Integration tests use Testcontainers and need Docker: `./mvnw verify`.
Dashboard unit tests: `cd frontend && npm test`.
The end-to-end demo check needs Docker Compose and `jq`: `bash deploy/smoke-test.sh`.

## Structure
- Backend: modular monolith under `com.opspilot.*`; see `docs/adr`.
- `frontend/`: the Angular dashboard; see `frontend/README.md`.
