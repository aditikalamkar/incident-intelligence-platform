package com.opspilot.common;

/** The request is refused whoever sends it; answered with 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
