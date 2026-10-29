package com.opspilot.ai;

import java.util.UUID;

/** Published when an investigation is created; it starts running once that transaction commits. */
record InvestigationRequested(UUID id, UUID tenantId, UUID incidentId, UUID requestedBy) {
}
