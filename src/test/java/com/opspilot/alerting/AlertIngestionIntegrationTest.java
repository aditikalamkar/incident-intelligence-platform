package com.opspilot.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import com.opspilot.Concurrently;
import com.opspilot.TestTenants;
import com.opspilot.TestcontainersConfiguration;
import com.opspilot.alerting.IngestionResult.Outcome;
import com.opspilot.incident.IncidentService;
import com.opspilot.incident.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AlertIngestionIntegrationTest {

    @Autowired
    AlertIngestionService ingestion;

    @Autowired
    IncidentService incidents;

    @Autowired
    JdbcClient jdbc;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = TestTenants.create(jdbc).tenantId();
    }

    @Test
    void retriedWebhookReturnsOriginalResultWithoutSideEffects() {
        IngestionResult first = ingestion.ingest(tenantId, "generic", alert("evt-1", "HighLatency"));
        IngestionResult retry = ingestion.ingest(tenantId, "generic", alert("evt-1", "HighLatency"));

        assertThat(first.outcome()).isEqualTo(Outcome.CREATED_INCIDENT);
        assertThat(retry.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(retry.alertId()).isEqualTo(first.alertId());
        assertThat(retry.incidentId()).isEqualTo(first.incidentId());
        assertThat(countAlerts()).isEqualTo(1);
        assertThat(incidents.get(tenantId, first.incidentId()).alertCount()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesOfOneEventStoreOneAlert() throws Exception {
        List<Future<IngestionResult>> results = Concurrently.run(20,
                i -> () -> ingestion.ingest(tenantId, "generic", alert("evt-same", "HighLatency")));

        List<Outcome> outcomes = outcomes(results);
        assertThat(outcomes).filteredOn(o -> o == Outcome.CREATED_INCIDENT).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o == Outcome.DUPLICATE).hasSize(19);
        assertThat(countAlerts()).isEqualTo(1);
        assertThat(countIncidents()).isEqualTo(1);
    }

    @Test
    void concurrentDistinctAlertsWithSameFingerprintGroupIntoOneIncident() throws Exception {
        List<Future<IngestionResult>> results = Concurrently.run(20,
                i -> () -> ingestion.ingest(tenantId, "generic", alert("evt-" + i, "DbConnectionErrors")));

        List<Outcome> outcomes = outcomes(results);
        assertThat(outcomes).filteredOn(o -> o == Outcome.CREATED_INCIDENT).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o == Outcome.GROUPED).hasSize(19);
        assertThat(countIncidents()).isEqualTo(1);
        UUID incidentId = results.getFirst().get().incidentId();
        assertThat(incidents.get(tenantId, incidentId).alertCount()).isEqualTo(20);
    }

    @Test
    void alertAfterResolutionOpensNewIncident() {
        IngestionResult first = ingestion.ingest(tenantId, "generic", alert("evt-a", "HighLatency"));
        incidents.resolve(tenantId, first.incidentId());

        IngestionResult next = ingestion.ingest(tenantId, "generic", alert("evt-b", "HighLatency"));

        assertThat(next.outcome()).isEqualTo(Outcome.CREATED_INCIDENT);
        assertThat(next.incidentId()).isNotEqualTo(first.incidentId());
    }

    private static GenericAlertRequest alert(String eventId, String alertName) {
        return new GenericAlertRequest(eventId, "payment-api", alertName, Severity.HIGH, "p95 latency above 2s",
                null, null);
    }

    private static List<Outcome> outcomes(List<Future<IngestionResult>> results) throws Exception {
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<IngestionResult> result : results) {
            outcomes.add(result.get().outcome());
        }
        return outcomes;
    }

    private long countAlerts() {
        return jdbc.sql("SELECT count(*) FROM alert WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single();
    }

    private long countIncidents() {
        return jdbc.sql("SELECT count(*) FROM incident WHERE tenant_id = :t").param("t", tenantId)
                .query(Long.class).single();
    }
}
