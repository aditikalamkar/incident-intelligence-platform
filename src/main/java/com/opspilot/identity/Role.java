package com.opspilot.identity;

public enum Role {
    /** Manages users, teams and API keys, and can do everything a responder can. */
    ADMIN,
    /** Works incidents: acknowledge, assign, comment, resolve. */
    RESPONDER
}
