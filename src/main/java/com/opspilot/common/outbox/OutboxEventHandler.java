package com.opspilot.common.outbox;

/**
 * Consumes outbox events. Runs inside the relay's transaction, so the handler's database writes
 * commit together with the event being marked processed: either both happen or neither does.
 * Side effects outside the database are not covered by that guarantee and must tolerate a retry.
 */
public interface OutboxEventHandler {

    boolean handles(String type);

    void handle(OutboxEvent event);
}
