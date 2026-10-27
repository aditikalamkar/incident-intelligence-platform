package com.opspilot.alerting;

import java.util.UUID;

public record IngestionResult(UUID alertId, UUID incidentId, Outcome outcome) {

    public enum Outcome {
        /** First alert for this fingerprint: a new incident was opened. */
        CREATED_INCIDENT,
        /** Attached to an already open incident with the same fingerprint. */
        GROUPED,
        /** Retry of an event already received; nothing was changed. */
        DUPLICATE
    }
}
