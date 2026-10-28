package com.opspilot.integration.alertmanager;

import com.opspilot.alerting.IngestionResult.Outcome;
import java.util.List;
import java.util.UUID;

/** One result per alert in the webhook, in the same order. */
public record AlertmanagerResponse(List<AlertResult> results) {

    public record AlertResult(String fingerprint, String status, Outcome outcome, UUID incidentId) {
    }
}
