package com.opspilot.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.TestFixtures.TestTenant;
import com.opspilot.alerting.AlertIngestionService;
import com.opspilot.alerting.GenericAlertRequest;
import com.opspilot.incident.Severity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class NotificationDispatcherIntegrationTest {

    record Row(String status, int attempts, String lastError, boolean backingOff) {
    }

    @Autowired
    NotificationDispatcher dispatcher;

    @Autowired
    AlertIngestionService ingestion;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    @Test
    void failedDeliveryIsRetriedWithBackoffThenMarkedFailed() {
        TestTenant tenant = fixtures.tenant();
        UUID incidentId = ingestion.ingest(tenant.tenantId(), "generic", new GenericAlertRequest(
                "evt-1", "payment-api", "HighLatency", Severity.CRITICAL, null, null, null)).incidentId();
        UUID notificationId = UUID.randomUUID();
        // Inserted directly: the API only accepts hooks.slack.com, and nothing listens on port 1.
        jdbc.sql("""
                INSERT INTO notification (id, tenant_id, incident_id, user_id, channel, target, message, dedupe_key)
                VALUES (:id, :tenantId, :incidentId, :userId, 'SLACK', 'http://127.0.0.1:1/unreachable', 'test', :key)
                """)
                .param("id", notificationId)
                .param("tenantId", tenant.tenantId())
                .param("incidentId", incidentId)
                .param("userId", tenant.admin().userId())
                .param("key", "test:" + notificationId)
                .update();

        dispatcher.dispatchPending();

        Row afterFirstFailure = row(notificationId);
        assertThat(afterFirstFailure.status()).isEqualTo("PENDING");
        assertThat(afterFirstFailure.attempts()).isEqualTo(1);
        assertThat(afterFirstFailure.lastError()).isNotBlank();
        assertThat(afterFirstFailure.backingOff()).isTrue();

        for (int retry = 0; retry < 2; retry++) {
            jdbc.sql("UPDATE notification SET next_attempt_at = now() WHERE id = :id").param("id", notificationId).update();
            dispatcher.dispatchPending();
        }

        assertThat(row(notificationId).status()).isEqualTo("FAILED");
        assertThat(row(notificationId).attempts()).isEqualTo(3);
    }

    private Row row(UUID id) {
        return jdbc.sql("""
                SELECT status, attempts, last_error, next_attempt_at > now() AS backing_off
                FROM notification WHERE id = :id
                """)
                .param("id", id)
                .query((rs, row) -> new Row(rs.getString("status"), rs.getInt("attempts"),
                        rs.getString("last_error"), rs.getBoolean("backing_off")))
                .single();
    }
}
