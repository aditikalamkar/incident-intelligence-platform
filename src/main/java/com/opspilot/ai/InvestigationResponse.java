package com.opspilot.ai;

import java.time.Instant;
import java.util.UUID;

/**
 * @param model   the model that answered; null until one has
 * @param result  null unless COMPLETED
 * @param failure why it failed, in words for the user; null unless FAILED
 */
public record InvestigationResponse(
        UUID id,
        UUID incidentId,
        InvestigationStatus status,
        UUID requestedBy,
        String model,
        Instant createdAt,
        Instant completedAt,
        InvestigationResult result,
        String failure) {
}
