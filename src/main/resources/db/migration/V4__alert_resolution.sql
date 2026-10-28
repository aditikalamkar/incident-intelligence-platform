-- Set when the monitoring system reports that the alert has cleared.
ALTER TABLE alert ADD COLUMN resolved_at TIMESTAMPTZ;

-- Answers "is any alert of this incident still firing?" before auto-resolving it.
CREATE INDEX idx_alert_incident_firing ON alert (incident_id) WHERE resolved_at IS NULL;
