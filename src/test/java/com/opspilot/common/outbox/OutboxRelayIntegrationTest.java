package com.opspilot.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opspilot.Concurrently;
import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.TestOutboxHandler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class OutboxRelayIntegrationTest {

    record Row(long id, String type, String status, int attempts, String lastError, boolean backingOff) {
    }

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxWriter outbox;

    @Autowired
    TestOutboxHandler handler;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    UUID tenantId;
    UUID aggregateId;

    @BeforeEach
    void setUp() {
        tenantId = fixtures.tenant().tenantId();
        aggregateId = UUID.randomUUID();
    }

    @Test
    void eventIsDiscardedWhenItsTransactionRollsBack() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            outbox.append(tenantId, aggregateId, TestOutboxHandler.RECORD, Map.of());
            throw new IllegalStateException("the business change failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rows()).isEmpty();
    }

    @Test
    void appendOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> outbox.append(tenantId, aggregateId, TestOutboxHandler.RECORD, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void concurrentRelaysHandleEachEventOnceAndInOrder() throws Exception {
        for (int i = 0; i < 10; i++) {
            append(TestOutboxHandler.RECORD);
        }

        List<Future<Object>> relays = Concurrently.run(4, i -> () -> {
            for (int attempt = 0; attempt < 200 && pending() > 0; attempt++) {
                relay.relayPending();
            }
            return null;
        });
        for (Future<Object> result : relays) {
            result.get();
        }

        assertThat(handler.handled(aggregateId)).containsExactlyElementsOf(rows().stream().map(Row::id).toList());
        assertThat(rows()).extracting(Row::status).containsOnly("PROCESSED");
    }

    @Test
    void failingEventIsRetriedWithBackoffThenParkedAndStopsBlockingLaterEvents() {
        append(TestOutboxHandler.FAIL);
        append(TestOutboxHandler.RECORD);

        relay.relayPending();

        Row failing = rows().getFirst();
        assertThat(failing.status()).isEqualTo("PENDING");
        assertThat(failing.attempts()).isEqualTo(1);
        assertThat(failing.lastError()).contains("simulated handler failure");
        assertThat(failing.backingOff()).isTrue();
        assertThat(handler.handled(aggregateId)).as("later event waits for the failing one").isEmpty();

        for (int retry = 0; retry < 2; retry++) {
            makeDue();
            relay.relayPending();
        }
        assertThat(rows().getFirst().status()).isEqualTo("FAILED");
        assertThat(rows().getFirst().attempts()).isEqualTo(3);

        relay.relayPending();
        assertThat(handler.handled(aggregateId)).hasSize(1);
    }

    private void append(String type) {
        tx.executeWithoutResult(status -> outbox.append(tenantId, aggregateId, type, Map.of()));
    }

    private void makeDue() {
        jdbc.sql("UPDATE outbox_event SET next_attempt_at = now() WHERE aggregate_id = :id")
                .param("id", aggregateId)
                .update();
    }

    private long pending() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE aggregate_id = :id AND status = 'PENDING'")
                .param("id", aggregateId)
                .query(Long.class)
                .single();
    }

    private List<Row> rows() {
        return jdbc.sql("""
                SELECT id, type, status, attempts, last_error, next_attempt_at > now() AS backing_off
                FROM outbox_event WHERE aggregate_id = :id ORDER BY id
                """)
                .param("id", aggregateId)
                .query((rs, row) -> new Row(rs.getLong("id"), rs.getString("type"), rs.getString("status"),
                        rs.getInt("attempts"), rs.getString("last_error"), rs.getBoolean("backing_off")))
                .list();
    }
}
