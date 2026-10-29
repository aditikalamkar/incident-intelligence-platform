-- Investigations by the AI assistant: a summary of an incident with findings and next steps.
CREATE TABLE investigation (
    id            UUID PRIMARY KEY,
    tenant_id     UUID         NOT NULL REFERENCES tenant (id),
    incident_id   UUID         NOT NULL REFERENCES incident (id),
    requested_by  UUID         NOT NULL REFERENCES app_user (id),
    status        VARCHAR(20)  NOT NULL,
    -- The model that answered, which may be a fallback model rather than the one configured
    model         VARCHAR(100),
    result        JSONB,
    failure       VARCHAR(500),
    input_tokens  BIGINT,
    output_tokens BIGINT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);

-- At most one running investigation per incident: a second request joins the running one
-- instead of paying for the same analysis twice.
CREATE UNIQUE INDEX uq_investigation_running ON investigation (incident_id) WHERE status = 'RUNNING';
CREATE INDEX idx_investigation_incident ON investigation (incident_id, created_at DESC);
-- Counts a tenant's investigations of the last 24 hours, for the daily limit
CREATE INDEX idx_investigation_tenant_created ON investigation (tenant_id, created_at);
