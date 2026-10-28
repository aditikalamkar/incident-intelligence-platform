package com.opspilot.alerting;

import java.util.UUID;

/** What happened to one alert. {@code alertId} and {@code incidentId} are null for {@link Outcome#UNKNOWN_ALERT}. */
public record IngestionResult(UUID alertId, UUID incidentId, Outcome outcome) {

    public enum Outcome {
        /** First alert for this fingerprint: a new incident was opened. */
        CREATED_INCIDENT,
        /** Attached to an already open incident with the same fingerprint. */
        GROUPED,
        /** Retry of an event already received; nothing was changed. */
        DUPLICATE,
        /** The alert cleared; its incident stays open (other alerts still firing, or auto-resolve is off). */
        ALERT_RESOLVED,
        /** The alert cleared and was the last one firing, so its incident was resolved automatically. */
        INCIDENT_RESOLVED,
        /** A resolution for an alert that was never received while firing; ignored. */
        UNKNOWN_ALERT
    }
}
