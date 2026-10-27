package com.opspilot.notification;

import java.util.UUID;

public record PendingNotification(UUID id, Channel channel, String target, String message) {
}
