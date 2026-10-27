package com.opspilot.servicecatalog;

import java.util.UUID;

/** @param teamId the owning team, whose escalation policy applies to the service's incidents; null if unowned */
public record ServiceResponse(UUID id, String name, UUID teamId) {
}
