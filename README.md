# OpsPilot

AI-assisted incident response platform for small engineering teams.

## Run locally
```
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
The `dev` profile seeds a demo tenant:
- API key for webhooks: `opspilot-dev-key`
- Admin login: `admin@demo.local` / `opspilot-dev-password`

Health: http://localhost:8080/actuator/health

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

## API
| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/v1/webhooks/{source}` | API key | 201 new incident, 200 grouped or duplicate |
| POST | `/api/v1/auth/signup` | none | Creates a tenant and its first admin |
| POST | `/api/v1/auth/login` | none | Returns a 1-hour JWT |
| GET | `/api/v1/me` | JWT | |
| GET, POST | `/api/v1/users` | JWT (POST: admin) | |
| GET, POST | `/api/v1/teams` | JWT (POST: admin) | |
| PUT, DELETE | `/api/v1/teams/{id}/members/{userId}` | JWT admin | |
| GET, POST, DELETE | `/api/v1/api-keys` | JWT admin | The raw key is shown only once, on create |
| GET | `/api/v1/incidents?status=&limit=` | JWT | Newest first |
| GET | `/api/v1/incidents/{id}` | JWT | |
| GET | `/api/v1/incidents/{id}/timeline` | JWT | Who did what, when |
| POST | `/api/v1/incidents/{id}/acknowledge` | JWT | Assigns the incident to you if unassigned |
| POST | `/api/v1/incidents/{id}/assign` | JWT | `{"assigneeId": "..."}` |
| POST | `/api/v1/incidents/{id}/comments` | JWT | `{"body": "..."}` |
| POST | `/api/v1/incidents/{id}/resolve` | JWT | |

All data is scoped to the caller's tenant. State changes return 409 on an invalid transition or a concurrent update.

## How reliability is enforced
- **Idempotent ingestion:** unique `(tenant_id, source, source_event_id)` on `alert` + `ON CONFLICT DO NOTHING`. A retry returns the original result.
- **Grouping:** a partial unique index allows one open incident per fingerprint; ingestion upserts into it, so concurrent alerts cannot open duplicate incidents.
- **Lifecycle:** state machine `TRIGGERED → ACKNOWLEDGED → RESOLVED` plus `@Version` optimistic locking; concurrent transitions get a 409.
- **Authentication:** API keys for machines, JWTs for people, on separate filter chains (see `docs/adr/0002-authentication.md`).

## Tests
Integration tests use Testcontainers and need Docker: `./mvnw verify`

## Structure
Modular monolith under `com.opspilot.*`; see `docs/adr`.
