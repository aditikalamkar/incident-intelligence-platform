package com.opspilot.notification;

public enum Channel {
    /** Posts to the owning team's Slack incoming webhook. */
    SLACK,
    /** Writes to the application log; used when the team has no Slack webhook configured. */
    LOG
}
