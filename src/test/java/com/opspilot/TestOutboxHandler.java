package com.opspilot;

import com.opspilot.common.outbox.OutboxEvent;
import com.opspilot.common.outbox.OutboxEventHandler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records the order in which "test.record" events are handled; always fails on "test.fail". */
public class TestOutboxHandler implements OutboxEventHandler {

    public static final String RECORD = "test.record";
    public static final String FAIL = "test.fail";

    private final Map<UUID, List<Long>> handled = new ConcurrentHashMap<>();

    @Override
    public boolean handles(String type) {
        return RECORD.equals(type) || FAIL.equals(type);
    }

    @Override
    public void handle(OutboxEvent event) {
        if (FAIL.equals(event.type())) {
            throw new IllegalStateException("simulated handler failure");
        }
        handled.computeIfAbsent(event.aggregateId(), id -> new CopyOnWriteArrayList<>()).add(event.id());
    }

    public List<Long> handled(UUID aggregateId) {
        return handled.getOrDefault(aggregateId, List.of());
    }
}
