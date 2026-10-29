package com.opspilot.common;

/** A usage limit was reached; answered with 429. */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
