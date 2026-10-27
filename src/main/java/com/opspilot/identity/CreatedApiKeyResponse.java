package com.opspilot.identity;

import java.time.Instant;
import java.util.UUID;

/** The only response that contains the raw key; it cannot be retrieved again. */
public record CreatedApiKeyResponse(UUID id, String label, String key, Instant createdAt) {
}
