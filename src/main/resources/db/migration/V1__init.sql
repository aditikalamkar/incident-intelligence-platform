CREATE TABLE tenant (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES tenant (id),
    email       VARCHAR(320) NOT NULL,
    name        VARCHAR(200) NOT NULL,
    role        VARCHAR(30)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, email)
);

CREATE TABLE team (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES tenant (id),
    name        VARCHAR(200) NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE TABLE service (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES tenant (id),
    team_id     UUID         REFERENCES team (id),
    name        VARCHAR(200) NOT NULL,
    UNIQUE (tenant_id, name)
);

CREATE TABLE api_key (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES tenant (id),
    key_hash    VARCHAR(64)  NOT NULL UNIQUE,
    label       VARCHAR(100) NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE incident (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL REFERENCES tenant (id),
    service_id      UUID         REFERENCES service (id),
    fingerprint     VARCHAR(128) NOT NULL,
    title           VARCHAR(300) NOT NULL,
    severity        VARCHAR(20)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    assignee_id     UUID         REFERENCES app_user (id),
    alert_count     INT          NOT NULL DEFAULT 1,
    version         BIGINT       NOT NULL DEFAULT 0,
    opened_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    acknowledged_at TIMESTAMPTZ,
    resolved_at     TIMESTAMPTZ
);

-- At most one open incident per fingerprint: the basis for race-safe grouping.
CREATE UNIQUE INDEX uq_incident_open_fingerprint
    ON incident (tenant_id, fingerprint)
    WHERE status <> 'RESOLVED';
CREATE INDEX idx_incident_tenant_status ON incident (tenant_id, status, opened_at DESC);

CREATE TABLE alert (
    id              UUID PRIMARY KEY,
    tenant_id       UUID         NOT NULL REFERENCES tenant (id),
    incident_id     UUID         REFERENCES incident (id),
    source          VARCHAR(50)  NOT NULL,
    source_event_id VARCHAR(200) NOT NULL,
    fingerprint     VARCHAR(128) NOT NULL,
    payload         JSONB        NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- Idempotency: a retried webhook cannot create a second alert.
    UNIQUE (tenant_id, source, source_event_id)
);

CREATE TABLE incident_event (
    id          UUID PRIMARY KEY,
    tenant_id   UUID         NOT NULL REFERENCES tenant (id),
    incident_id UUID         NOT NULL REFERENCES incident (id),
    type        VARCHAR(50)  NOT NULL,
    actor_id    UUID,
    details     JSONB,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_incident_event_incident ON incident_event (incident_id, created_at);
