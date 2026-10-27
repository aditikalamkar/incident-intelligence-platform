package com.opspilot.notification;

/** A delivery mechanism. {@link #send} throws on failure so the dispatcher can retry. */
public interface NotificationChannel {

    Channel type();

    void send(PendingNotification notification);
}
