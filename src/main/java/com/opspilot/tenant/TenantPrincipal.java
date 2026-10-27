package com.opspilot.tenant;

import java.util.UUID;

/** The authenticated caller. Every data access is scoped by {@link #tenantId()}. */
public record TenantPrincipal(UUID tenantId, UUID apiKeyId) {
}
