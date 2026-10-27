# ADR 0003: Transactional outbox and database-backed escalation

Status: accepted

## Context
When an incident opens, its team must be paged, and paged again at the next level if nobody acknowledges in time. A page must not be lost if the process crashes, must not be sent twice by two instances, and must not be sent for an incident that was already acknowledged. We run on PostgreSQL only; Kafka is not justified yet.

## Decision
- **Transactional outbox.** The incident module writes an `outbox_event` row (`incident.opened`, `.acknowledged`, `.resolved`) in the same transaction as the state change. A relay delivers events to in-process handlers. Each event is claimed with `FOR UPDATE SKIP LOCKED` and handled in its own transaction, together with marking it processed.
- **Per-incident ordering.** An event is eligible only when no earlier event for the same incident is pending, so "opened" is always handled before "acknowledged".
- **Retries and parking.** A failing event is retried with exponential backoff (5 s doubling to 5 min) and parked as `FAILED` after `max-attempts`. It holds back later events of the same incident, never other incidents.
- **Durable schedule.** `incident_escalation` holds the next level and its due time. A worker pages due levels; the schedule lives in the database, so it survives restarts. Due times use the database clock.
- **Re-check before paging.** The worker reads the incident with `FOR SHARE` before paging. An acknowledgement whose event is not yet relayed still stops escalation, and a concurrent acknowledgement waits for the page to commit instead of racing it.
- **Notifications as their own outbox.** Pages are rows in `notification`, written in the escalation transaction with a unique dedupe key per (incident, level, user). A dispatcher sends them (Slack webhook, or log) with the same claim/retry/park pattern.
- **Multi-instance safety.** Every worker claims rows with `SKIP LOCKED`; no leader election is needed.

## Consequences
- Database effects of event handling are exactly-once: handler writes and "processed" commit together.
- Slack delivery is at-least-once: a crash after Slack accepts a message but before it is marked sent causes a resend. Slack webhooks have no idempotency key to prevent this.
- Latency from alert to first page is up to two poll intervals (1 s each by default).
- Processed outbox rows and sent notifications accumulate; a retention job is needed later.
- Moving to Kafka replaces only the relay's dispatch step. Consumers then become at-least-once and need their own deduplication (the escalation handler already is idempotent).
