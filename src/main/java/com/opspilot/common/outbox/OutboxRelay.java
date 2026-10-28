package com.opspilot.common.outbox;

import com.opspilot.common.DatabaseGauges;
import com.opspilot.common.WorkerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers outbox events to in-process handlers. Safe to run on several instances at once:
 * each event is claimed with {@code FOR UPDATE SKIP LOCKED}. Publishing to Kafka later only
 * replaces the handler dispatch; claiming, ordering and retries stay the same.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final List<OutboxEventHandler> handlers;
    private final WorkerProperties properties;
    private final Counter processedCounter;
    private final Counter failureCounter;

    public OutboxRelay(JdbcClient jdbc, TransactionTemplate tx, List<OutboxEventHandler> handlers,
            WorkerProperties properties, MeterRegistry registry, DatabaseGauges gauges) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.handlers = handlers;
        this.properties = properties;
        this.processedCounter = registry.counter("opspilot.outbox.processed");
        this.failureCounter = registry.counter("opspilot.outbox.failures");
        gauges.register("opspilot.outbox.pending", "Events waiting to be relayed",
                "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'");
        gauges.register("opspilot.outbox.failed", "Events parked after exhausting their retries",
                "SELECT count(*) FROM outbox_event WHERE status = 'FAILED'");
        gauges.registerSeconds("opspilot.outbox.oldest.pending.age", "How far behind the relay is",
                "SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at)), 0) FROM outbox_event WHERE status = 'PENDING'");
    }

    /**
     * An event is eligible only when no earlier event for the same aggregate is still pending, so
     * handlers see an incident's events in the order they happened. A failing event holds back
     * later events of its own incident (until it succeeds or is parked), but never other incidents.
     *
     * @return the number of events processed
     */
    @Scheduled(fixedDelayString = "${opspilot.workers.poll-interval:PT1S}")
    public int relayPending() {
        List<Long> eligible = jdbc.sql("""
                SELECT e.id FROM outbox_event e
                WHERE e.status = 'PENDING' AND e.next_attempt_at <= now()
                  AND NOT EXISTS (SELECT 1 FROM outbox_event earlier
                                  WHERE earlier.aggregate_id = e.aggregate_id
                                    AND earlier.status = 'PENDING'
                                    AND earlier.id < e.id)
                ORDER BY e.id
                LIMIT :limit
                """)
                .param("limit", properties.batchSize())
                .query(Long.class)
                .list();
        int processed = 0;
        for (long id : eligible) {
            if (process(id)) {
                processed++;
                processedCounter.increment();
            }
        }
        return processed;
    }

    private boolean process(long id) {
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                Optional<OutboxEvent> claimed = jdbc.sql("""
                        SELECT id, tenant_id, aggregate_id, type, payload FROM outbox_event
                        WHERE id = :id AND status = 'PENDING'
                        FOR UPDATE SKIP LOCKED
                        """)
                        .param("id", id)
                        .query((rs, row) -> new OutboxEvent(
                                rs.getLong("id"),
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("aggregate_id", UUID.class),
                                rs.getString("type"),
                                rs.getString("payload")))
                        .optional();
                if (claimed.isEmpty()) {
                    return false; // another instance holds it, or it is already done
                }
                OutboxEvent event = claimed.get();
                for (OutboxEventHandler handler : handlers) {
                    if (handler.handles(event.type())) {
                        handler.handle(event);
                    }
                }
                jdbc.sql("""
                        UPDATE outbox_event SET status = 'PROCESSED', processed_at = now(), attempts = attempts + 1
                        WHERE id = :id
                        """)
                        .param("id", id)
                        .update();
                return true;
            }));
        } catch (RuntimeException e) {
            log.warn("Outbox event {} failed; will retry", id, e);
            failureCounter.increment();
            recordFailure(id, e);
            return false;
        }
    }

    private void recordFailure(long id, RuntimeException failure) {
        String error = String.valueOf(failure);
        tx.executeWithoutResult(status -> jdbc.sql("""
                UPDATE outbox_event SET
                    attempts = attempts + 1,
                    last_error = :error,
                    status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                    next_attempt_at = now() + LEAST(5 * power(2, attempts), 300) * interval '1 second'
                WHERE id = :id AND status = 'PENDING'
                """)
                .param("error", error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH)))
                .param("maxAttempts", properties.maxAttempts())
                .param("id", id)
                .update());
    }
}
