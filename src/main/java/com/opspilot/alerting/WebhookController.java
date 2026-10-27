package com.opspilot.alerting;

import com.opspilot.identity.ApiKeyPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

    private final AlertIngestionService ingestion;

    public WebhookController(AlertIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    /** 201 when a new incident was opened; 200 when grouped or a duplicate, so senders stop retrying. */
    @PostMapping("/{source}")
    ResponseEntity<IngestionResult> receive(@AuthenticationPrincipal ApiKeyPrincipal principal,
            @PathVariable @Pattern(regexp = "[a-z0-9-]{1,50}") String source,
            @Valid @RequestBody GenericAlertRequest request) {
        IngestionResult result = ingestion.ingest(principal.tenantId(), source, request);
        HttpStatus status = result.outcome() == IngestionResult.Outcome.CREATED_INCIDENT
                ? HttpStatus.CREATED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }
}
