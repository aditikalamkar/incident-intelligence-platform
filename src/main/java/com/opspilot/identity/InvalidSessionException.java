package com.opspilot.identity;

/** A refresh token that cannot be exchanged: the browser must sign in again. */
class InvalidSessionException extends RuntimeException {

    enum Reason {
        MISSING, UNKNOWN, EXPIRED, REVOKED, REUSED
    }

    private final Reason reason;

    InvalidSessionException(Reason reason) {
        super("The session has ended; sign in again");
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }
}
