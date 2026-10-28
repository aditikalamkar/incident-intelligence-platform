package com.opspilot.integration.alertmanager;

import com.opspilot.alerting.AlertIngestionService;
import com.opspilot.alerting.IngestionResult;
import com.opspilot.identity.ApiKeyPrincipal;
import com.opspilot.integration.alertmanager.AlertmanagerResponse.AlertResult;
import com.opspilot.integration.alertmanager.AlertmanagerWebhook.Alert;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receiver for Prometheus Alertmanager's webhook. Configure it as a {@code webhook_configs} URL with
 * the API key as a Bearer token; see the README.
 */
@RestController
@RequestMapping("/api/v1/webhooks/alertmanager")
public class AlertmanagerController {

    private final AlertIngestionService ingestion;

    public AlertmanagerController(AlertIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    /**
     * Each alert is processed in its own transaction. If one fails, the request fails and
     * Alertmanager retries the whole batch; alerts that already succeeded then report DUPLICATE.
     *
     * @param autoResolve resolve an incident when its last firing alert clears (default true)
     */
    @PostMapping
    public AlertmanagerResponse receive(@AuthenticationPrincipal ApiKeyPrincipal principal,
            @RequestParam(defaultValue = "true") boolean autoResolve,
            @Valid @RequestBody AlertmanagerWebhook webhook) {
        List<AlertResult> results = new ArrayList<>();
        for (Alert alert : webhook.alerts()) {
            IngestionResult result = alert.isResolved()
                    ? ingestion.resolve(principal.tenantId(), AlertmanagerMapping.SOURCE,
                            AlertmanagerMapping.eventId(alert), autoResolve)
                    : ingestion.ingest(principal.tenantId(), AlertmanagerMapping.SOURCE,
                            AlertmanagerMapping.toRequest(alert), alert);
            results.add(new AlertResult(alert.fingerprint(), alert.status(), result.outcome(), result.incidentId()));
        }
        return new AlertmanagerResponse(results);
    }
}
