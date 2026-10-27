package com.opspilot.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LogNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationChannel.class);

    @Override
    public Channel type() {
        return Channel.LOG;
    }

    @Override
    public void send(PendingNotification notification) {
        log.info("Notification {}: {}", notification.id(), notification.message());
    }
}
