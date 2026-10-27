package com.opspilot.identity;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyResponse(UUID id, String label, boolean revoked, Instant createdAt) {
}
