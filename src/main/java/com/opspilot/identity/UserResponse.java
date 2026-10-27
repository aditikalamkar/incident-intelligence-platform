package com.opspilot.identity;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, String name, Role role, Instant createdAt) {
}
