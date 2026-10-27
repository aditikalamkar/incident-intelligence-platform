package com.opspilot.identity;

import java.util.List;
import java.util.UUID;

public record TeamResponse(UUID id, String name, List<UUID> memberIds) {
}
