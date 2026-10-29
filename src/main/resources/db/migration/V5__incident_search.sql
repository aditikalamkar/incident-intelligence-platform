-- Matches the search order (newest first, id as tie-breaker), so each page is an index range scan.
CREATE INDEX idx_incident_tenant_opened ON incident (tenant_id, opened_at DESC, id DESC);

-- Common filters: "my incidents" and "this service's incidents".
CREATE INDEX idx_incident_tenant_assignee ON incident (tenant_id, assignee_id, opened_at DESC);
CREATE INDEX idx_incident_tenant_service ON incident (tenant_id, service_id, opened_at DESC);
