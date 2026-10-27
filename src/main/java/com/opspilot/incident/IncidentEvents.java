package com.opspilot.incident;

import java.util.UUID;

/** Outbox event types published by the incident module. The outbox aggregate ID is the incident ID. */
public final class IncidentEvents {

    public static final String OPENED = "incident.opened";
    public static final String ACKNOWLEDGED = "incident.acknowledged";
    public static final String RESOLVED = "incident.resolved";

    public record Opened(UUID incidentId, UUID serviceId, String title, Severity severity) {
    }

    public record Acknowledged(UUID incidentId, UUID actorId) {
    }

    public record Resolved(UUID incidentId, UUID actorId) {
    }

    private IncidentEvents() {
    }
}
