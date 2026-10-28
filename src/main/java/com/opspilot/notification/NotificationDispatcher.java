package com.opspilot.notification;

import com.opspilot.common.DatabaseGauges;
import com.opspilot.common.WorkerProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers pending notifications, retrying failures with exponential backoff. Delivery is
 * at-least-once: if the process dies after Slack accepted a message but before it is marked
 * sent, it is sent again. External APIs like Slack webhooks offer no idempotency key to prevent that.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Map<Channel, NotificationChannel> channels = new EnumMap<>(Channel.class);
    private final WorkerProperties properties;
    private final MeterRegistry registry;
    private final Counter failureCounter;

    public NotificationDispatcher(JdbcClient jdbc, TransactionTemplate tx, List<NotificationChannel> channels,
            WorkerProperties properties, MeterRegistry registry, DatabaseGauges gauges) {
        this.jdbc = jdbc;
        this.tx = tx;
        channels.forEach(channel -> this.channels.put(channel.type(), channel));
        this.properties = properties;
        this.registry = registry;
        this.failureCounter = registry.counter("opspilot.notifications.failures");
        gauges.register("opspilot.notifications.pending", "Notifications waiting to be sent",
                "SELECT count(*) FROM notification WHERE status = 'PENDING'");
        gauges.register("opspilot.notifications.failed", "Notifications given up on after exhausting their retries",
                "SELECT count(*) FROM notification WHERE status = 'FAILED'");
    }

    /** @return the number of notifications sent */
    @Scheduled(fixedDelayString = "${opspilot.workers.poll-interval:PT1S}")
    public int dispatchPending() {
        List<UUID> due = jdbc.sql("""
                SELECT id FROM notification WHERE status = 'PENDING' AND next_attempt_at <= now()
                ORDER BY created_at LIMIT :limit
                """)
                .param("limit", properties.batchSize())
                .query(UUID.class)
                .list();
        int sent = 0;
        for (UUID id : due) {
            Channel channel = send(id);
            if (channel != null) {
                sent++;
                registry.counter("opspilot.notifications.sent", "channel", channel.name()).increment();
            }
        }
        return sent;
    }

    /** @return the channel it was sent through, or null if it was not sent */
    private Channel send(UUID id) {
        try {
            return tx.execute(status -> {
                Optional<PendingNotification> claimed = jdbc.sql("""
                        SELECT id, channel, target, message FROM notification
                        WHERE id = :id AND status = 'PENDING'
                        FOR UPDATE SKIP LOCKED
                        """)
                        .param("id", id)
                        .query((rs, row) -> new PendingNotification(
                                rs.getObject("id", UUID.class),
                                Channel.valueOf(rs.getString("channel")),
                                rs.getString("target"),
                                rs.getString("message")))
                        .optional();
                if (claimed.isEmpty()) {
                    return null; // another instance is sending it, or it is already sent
                }
                channels.get(claimed.get().channel()).send(claimed.get());
                jdbc.sql("UPDATE notification SET status = 'SENT', sent_at = now(), attempts = attempts + 1 WHERE id = :id")
                        .param("id", id)
                        .update();
                return claimed.get().channel();
            });
        } catch (RuntimeException e) {
            log.warn("Notification {} failed; will retry", id, e);
            failureCounter.increment();
            recordFailure(id, e);
            return null;
        }
    }

    private void recordFailure(UUID id, RuntimeException failure) {
        String error = String.valueOf(failure);
        tx.executeWithoutResult(status -> jdbc.sql("""
                UPDATE notification SET
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
