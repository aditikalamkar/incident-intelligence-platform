package com.opspilot.common;

/** A request that is well-formed JSON but asks for something invalid, such as a malformed cursor. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
