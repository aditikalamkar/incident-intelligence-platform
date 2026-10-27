-- Transactional outbox: domain events written in the same transaction as the change they describe.
CREATE TABLE outbox_event (
    id              BIGSERIAL PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    aggregate_id    UUID         NOT NULL,
    type            VARCHAR(100) NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_due ON outbox_event (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox_event (aggregate_id, id) WHERE status = 'PENDING';

ALTER TABLE team ADD COLUMN slack_webhook_url VARCHAR(500);

CREATE TABLE escalation_policy (
    id         UUID PRIMARY KEY,
    tenant_id  UUID        NOT NULL REFERENCES tenant (id),
    team_id    UUID        NOT NULL UNIQUE REFERENCES team (id) ON DELETE CASCADE,
    steps      JSONB       NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Durable escalation schedule: one row per incident whose team has a policy.
-- Kept after it stops (stopped_at set) so a redelivered "opened" event cannot restart it.
CREATE TABLE incident_escalation (
    incident_id UUID PRIMARY KEY REFERENCES incident (id),
    tenant_id   UUID        NOT NULL,
    team_id     UUID        NOT NULL,
    next_step   INT         NOT NULL,
    due_at      TIMESTAMPTZ NOT NULL,
    stopped_at  TIMESTAMPTZ,
    stop_reason VARCHAR(30),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_incident_escalation_due ON incident_escalation (due_at) WHERE stopped_at IS NULL;

CREATE TABLE notification (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL,
    incident_id     UUID         NOT NULL REFERENCES incident (id),
    user_id         UUID         NOT NULL REFERENCES app_user (id),
    channel         VARCHAR(20)  NOT NULL,
    target          VARCHAR(500),
    message         TEXT         NOT NULL,
    -- Makes paging idempotent: the same escalation level never notifies the same person twice.
    dedupe_key      VARCHAR(200) NOT NULL UNIQUE,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
);
CREATE INDEX idx_notification_due ON notification (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_notification_incident ON notification (incident_id);
