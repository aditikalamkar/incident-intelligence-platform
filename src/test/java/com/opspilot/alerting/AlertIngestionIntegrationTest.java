package com.opspilot.alerting;

import static org.assertj.core.api.Assertions.assertThat;

import com.opspilot.Concurrently;
import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.TestFixtures.TestTenant;
import com.opspilot.alerting.IngestionResult.Outcome;
import com.opspilot.incident.IncidentService;
import com.opspilot.incident.IncidentStatus;
import com.opspilot.incident.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class AlertIngestionIntegrationTest {

    @Autowired
    AlertIngestionService ingestion;

    @Autowired
    IncidentService incidents;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    TestTenant tenant;
    UUID tenantId;

    @BeforeEach
    void setUp() {
        tenant = fixtures.tenant();
        tenantId = tenant.tenantId();
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
        incidents.resolve(tenantId, first.incidentId(), tenant.admin().userId());

        IngestionResult next = ingestion.ingest(tenantId, "generic", alert("evt-b", "HighLatency"));

        assertThat(next.outcome()).isEqualTo(Outcome.CREATED_INCIDENT);
        assertThat(next.incidentId()).isNotEqualTo(first.incidentId());
    }

    /**
     * Write-skew check: each of the last two alerts clears in its own transaction at the same time.
     * Without the incident row lock, each would still see the other alert firing and neither would
     * resolve the incident. Repeated to give the threads many chances to interleave.
     */
    @Test
    void lastTwoAlertsClearingConcurrentlyStillResolveTheIncident() throws Exception {
        for (int round = 0; round < 10; round++) {
            String first = "first-" + round;
            String second = "second-" + round;
            UUID incidentId = ingestion.ingest(tenantId, "generic", alert(first, "Flapping" + round)).incidentId();
            ingestion.ingest(tenantId, "generic", alert(second, "Flapping" + round));

            List<Future<IngestionResult>> results = Concurrently.run(2,
                    i -> () -> ingestion.resolve(tenantId, "generic", i == 0 ? first : second, true));

            assertThat(outcomes(results)).containsExactlyInAnyOrder(Outcome.ALERT_RESOLVED, Outcome.INCIDENT_RESOLVED);
            assertThat(incidents.get(tenantId, incidentId).status()).isEqualTo(IncidentStatus.RESOLVED);
        }
    }

    @Test
    void resolvingIsIdempotentAndIgnoresUnknownAlerts() {
        ingestion.ingest(tenantId, "generic", alert("evt-1", "HighLatency"));

        assertThat(ingestion.resolve(tenantId, "generic", "evt-1", true).outcome()).isEqualTo(Outcome.INCIDENT_RESOLVED);
        assertThat(ingestion.resolve(tenantId, "generic", "evt-1", true).outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(ingestion.resolve(tenantId, "generic", "never-fired", true).outcome())
                .isEqualTo(Outcome.UNKNOWN_ALERT);
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
