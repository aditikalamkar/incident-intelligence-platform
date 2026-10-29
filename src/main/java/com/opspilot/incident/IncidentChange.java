package com.opspilot.incident;

import java.util.UUID;

/**
 * Published with PostgreSQL NOTIFY on {@link #CHANNEL} whenever an incident's timeline gains an event.
 * NOTIFY is transactional: every instance's {@link IncidentChangeListener} receives it once the change
 * commits, and never if it rolls back.
 */
record IncidentChange(UUID tenantId, UUID incidentId, UUID eventId) {

    static final String CHANNEL = "incident_change";
}
