package com.opspilot.identity;

import java.util.UUID;

/** A monitoring system calling the webhook with an API key. Every data access is scoped by {@link #tenantId()}. */
public record ApiKeyPrincipal(UUID tenantId, UUID apiKeyId) {
}
