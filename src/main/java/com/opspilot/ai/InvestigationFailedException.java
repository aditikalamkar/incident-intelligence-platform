package com.opspilot.ai;

/** An investigation could not produce an analysis. The message is shown to the user, so it says what to do. */
public class InvestigationFailedException extends RuntimeException {

    public InvestigationFailedException(String message) {
        super(message);
    }

    public InvestigationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
