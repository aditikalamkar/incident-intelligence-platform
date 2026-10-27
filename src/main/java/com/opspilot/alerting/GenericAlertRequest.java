package com.opspilot.alerting;

import com.opspilot.incident.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Payload of the generic webhook.
 *
 * @param eventId     stable ID from the sender; retries must reuse it (idempotency key)
 * @param fingerprint optional grouping key; defaults to a hash of source, service and alert name
 */
public record GenericAlertRequest(
        @NotBlank @Size(max = 200) String eventId,
        @NotBlank @Size(max = 100) String service,
        @NotBlank @Size(max = 150) String alertName,
        Severity severity,
        @Size(max = 2000) String summary,
        @Size(max = 128) String fingerprint,
        Map<String, String> labels) {
}
