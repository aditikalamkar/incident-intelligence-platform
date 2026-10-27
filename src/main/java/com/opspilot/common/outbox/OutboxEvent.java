package com.opspilot.common.outbox;

import java.util.UUID;

public record OutboxEvent(long id, UUID tenantId, UUID aggregateId, String type, String payload) {
}
