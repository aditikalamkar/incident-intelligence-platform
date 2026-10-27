package com.opspilot.incident;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record TimelineEventResponse(UUID id, IncidentEventType type, UUID actorId, JsonNode details, Instant createdAt) {
}
