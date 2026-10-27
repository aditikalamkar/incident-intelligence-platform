package com.opspilot.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Background workers (outbox relay, escalation, notification dispatch). The poll interval is read
 * directly by {@code @Scheduled} from {@code opspilot.workers.poll-interval}.
 *
 * @param enabled     false turns off scheduling, e.g. in tests that drive the workers by hand
 * @param maxAttempts failed work is retried with exponential backoff, then parked as FAILED
 */
@ConfigurationProperties("opspilot.workers")
public record WorkerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100") int batchSize,
        @DefaultValue("10") int maxAttempts) {
}
