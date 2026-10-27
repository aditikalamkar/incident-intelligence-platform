package com.opspilot.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opspilot.Concurrently;
import com.opspilot.TestTenants;
import com.opspilot.TestcontainersConfiguration;
import com.opspilot.alerting.AlertIngestionService;
import com.opspilot.alerting.GenericAlertRequest;
import com.opspilot.common.ConflictException;
import com.opspilot.common.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IncidentLifecycleIntegrationTest {

    @Autowired
    AlertIngestionService ingestion;

    @Autowired
    IncidentService incidents;

    @Autowired
    JdbcClient jdbc;

    UUID tenantId;
    UUID incidentId;

    @BeforeEach
    void setUp() {
        tenantId = TestTenants.create(jdbc).tenantId();
        incidentId = ingestion.ingest(tenantId, "generic",
                new GenericAlertRequest("evt-1", "payment-api", "HighLatency", Severity.CRITICAL, null, null, null))
                .incidentId();
    }

    @Test
    void acknowledgeThenResolveRecordsTimeline() {
        IncidentResponse acked = incidents.acknowledge(tenantId, incidentId);
        IncidentResponse resolved = incidents.resolve(tenantId, incidentId);

        assertThat(acked.status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(resolved.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.version()).isGreaterThan(acked.version());
        assertThat(incidents.timeline(tenantId, incidentId)).extracting(TimelineEventResponse::type)
                .containsExactly(IncidentEventType.INCIDENT_CREATED, IncidentEventType.ACKNOWLEDGED,
                        IncidentEventType.RESOLVED);
    }

    @Test
    void resolvedIncidentCannotBeAcknowledged() {
        incidents.resolve(tenantId, incidentId);

        assertThatThrownBy(() -> incidents.acknowledge(tenantId, incidentId))
                .isInstanceOf(IllegalTransitionException.class);
    }

    @Test
    void concurrentAcknowledgeHasExactlyOneWinner() throws Exception {
        List<Future<IncidentResponse>> results = Concurrently.run(2,
                i -> () -> incidents.acknowledge(tenantId, incidentId));

        int successes = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Future<IncidentResponse> result : results) {
            try {
                result.get();
                successes++;
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }

        assertThat(successes).isEqualTo(1);
        assertThat(failures).singleElement().satisfies(f -> assertThat(f)
                .isInstanceOfAny(OptimisticLockingFailureException.class, ConflictException.class));
        assertThat(incidents.timeline(tenantId, incidentId))
                .filteredOn(e -> e.type() == IncidentEventType.ACKNOWLEDGED).hasSize(1);
    }

    @Test
    void otherTenantCannotSeeIncident() {
        UUID otherTenant = TestTenants.create(jdbc).tenantId();

        assertThatThrownBy(() -> incidents.get(otherTenant, incidentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
