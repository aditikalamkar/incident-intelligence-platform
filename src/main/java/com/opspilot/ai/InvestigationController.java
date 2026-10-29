package com.opspilot.ai;

import com.opspilot.identity.UserPrincipal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InvestigationController {

    private final InvestigationService investigations;

    InvestigationController(InvestigationService investigations) {
        this.investigations = investigations;
    }

    /** Whether the assistant is on, so clients can hide it when it is not. */
    @GetMapping("/api/v1/assistant")
    public AssistantStatus status() {
        return investigations.status();
    }

    /**
     * Starts an investigation, or returns the one already running for this incident. It finishes in the
     * background, usually within a minute; the incident's timeline then gains an INVESTIGATION_COMPLETED
     * or INVESTIGATION_FAILED event, which live clients receive.
     */
    @PostMapping("/api/v1/incidents/{incidentId}/investigations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public InvestigationResponse investigate(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID incidentId) {
        return investigations.start(user.tenantId(), incidentId, user.userId());
    }

    /** 204 if the incident has never been investigated. */
    @GetMapping("/api/v1/incidents/{incidentId}/investigations/latest")
    public ResponseEntity<InvestigationResponse> latest(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable UUID incidentId) {
        return investigations.latest(user.tenantId(), incidentId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
