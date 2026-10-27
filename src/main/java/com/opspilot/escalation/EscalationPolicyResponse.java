package com.opspilot.escalation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EscalationPolicyResponse(UUID teamId, List<EscalationStep> steps, Instant updatedAt) {
}
