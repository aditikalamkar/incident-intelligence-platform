# ADR 0004: Live updates with Server-Sent Events and PostgreSQL LISTEN/NOTIFY

Status: accepted

## Context
The dashboard must show new incidents and changes (acknowledged, assigned, commented, escalated, resolved) within a second, without polling. Several OpsPilot instances run behind a load balancer: a browser is connected to one instance, but the change may happen on another. We run on PostgreSQL only.

The transactional outbox (ADR 0003) is not the right transport: it delivers each event to exactly one instance, while here every instance must hear every change.

## Decision
- **Server-Sent Events** at `GET /api/v1/incidents/stream`. Updates flow one way only (server to browser), so SSE suffices: plain HTTP, works through proxies, no WebSocket protocol to secure.
- **Fan-out through PostgreSQL `NOTIFY`.** Every incident change is written to the timeline, and the same code sends `pg_notify('incident_change', {tenantId, incidentId, eventId})` in the same transaction. PostgreSQL delivers it to every listening instance only if and when the transaction commits, in commit order. Each instance holds one connection that `LISTEN`s and pushes changes to its own browsers.
- **The stream carries current state.** For each change the instance loads the incident once and sends `{incident, event}` to every connected user of that tenant.
- **No replay; reload instead.** The stream sends `ready` when connected, and the client then loads the current list. NOTIFY is not stored, so when the listener's connection drops and comes back, every client is sent `resync` and reloads.
- **Isolation between clients.** Each connection has its own sender (a virtual thread) and a bounded queue. A client too slow to keep up has its stream closed; it reconnects and reloads. Heartbeats every 25 seconds keep proxies from closing idle connections and reveal clients that went away.
- **Authentication.** The stream uses the normal `Authorization: Bearer` header, never a token in the URL (URLs end up in logs). It ends when the token expires, so access does not outlive the credential. Browsers therefore read it with `fetch` streaming rather than `EventSource`, which cannot send headers.
- **Shutdown.** Streams are closed before the web server's graceful shutdown begins, so deploys do not wait for long-lived requests; clients reconnect to another instance.

## Consequences
- Delivery is at-most-once per connection; correctness relies on clients reloading on `ready` and `resync`. Clients must apply an update only if its `version` and `alertCount` are not lower than what they hold, since an update and a reload can arrive in either order.
- Each instance uses one database connection for listening.
- PostgreSQL serialises commits of transactions that sent NOTIFY. This is negligible at our write rate. At far higher volume, or when other consumers need the same changes, the outbox relay would publish to Redis or Kafka, and only the listener would change.
- All users of a tenant receive all of its incident changes; per-user filtering happens in the browser.
