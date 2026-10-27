# OpsPilot

AI-assisted incident response platform for small engineering teams.

## Run locally
```
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
The `dev` profile seeds a demo tenant with API key `opspilot-dev-key`.
Health: http://localhost:8080/actuator/health

## Try it
```
# Send an alert (repeat it: the second call returns DUPLICATE)
curl -X POST localhost:8080/api/v1/webhooks/generic \
  -H "X-API-Key: opspilot-dev-key" -H "Content-Type: application/json" \
  -d '{"eventId":"evt-1","service":"payment-api","alertName":"HighLatency","severity":"CRITICAL"}'

curl localhost:8080/api/v1/incidents -H "X-API-Key: opspilot-dev-key"
curl -X POST localhost:8080/api/v1/incidents/{id}/acknowledge -H "X-API-Key: opspilot-dev-key"
curl -X POST localhost:8080/api/v1/incidents/{id}/resolve -H "X-API-Key: opspilot-dev-key"
curl localhost:8080/api/v1/incidents/{id}/timeline -H "X-API-Key: opspilot-dev-key"
```

## API
| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/webhooks/{source}` | 201 new incident, 200 grouped or duplicate |
| GET | `/api/v1/incidents?status=&limit=` | Newest first |
| GET | `/api/v1/incidents/{id}` | |
| GET | `/api/v1/incidents/{id}/timeline` | |
| POST | `/api/v1/incidents/{id}/acknowledge` | 409 on invalid transition or concurrent update |
| POST | `/api/v1/incidents/{id}/resolve` | 409 on invalid transition or concurrent update |

All `/api` calls require the `X-API-Key` header; data is scoped to the key's tenant.

## How reliability is enforced
- **Idempotent ingestion:** unique `(tenant_id, source, source_event_id)` on `alert` + `ON CONFLICT DO NOTHING`. A retry returns the original result.
- **Grouping:** a partial unique index allows one open incident per fingerprint; ingestion upserts into it, so concurrent alerts cannot open duplicate incidents.
- **Lifecycle:** state machine `TRIGGERED → ACKNOWLEDGED → RESOLVED` plus `@Version` optimistic locking; concurrent transitions get a 409.

## Tests
Integration tests use Testcontainers and need Docker: `./mvnw verify`

## Structure
Modular monolith under `com.opspilot.*`; see `docs/adr`.
