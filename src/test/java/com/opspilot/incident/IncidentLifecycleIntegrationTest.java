package com.opspilot.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.opspilot.Concurrently;
import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.alerting.AlertIngestionService;
import com.opspilot.alerting.GenericAlertRequest;
import com.opspilot.common.ConflictException;
import com.opspilot.common.ResourceNotFoundException;
import com.opspilot.identity.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

@IntegrationTest
class IncidentLifecycleIntegrationTest {

    @Autowired
    AlertIngestionService ingestion;

    @Autowired
    IncidentService incidents;

    @Autowired
    TestFixtures fixtures;

    UUID tenantId;
    UUID incidentId;
    UUID alice;
    UUID bob;

    @BeforeEach
    void setUp() {
        tenantId = fixtures.tenant().tenantId();
        alice = fixtures.user(tenantId, Role.RESPONDER).userId();
        bob = fixtures.user(tenantId, Role.RESPONDER).userId();
        incidentId = ingestion.ingest(tenantId, "generic",
                new GenericAlertRequest("evt-1", "payment-api", "HighLatency", Severity.CRITICAL, null, null, null))
                .incidentId();
    }

    @Test
    void acknowledgeAssignsUnassignedIncidentToActorAndRecordsWhoActed() {
        IncidentResponse acked = incidents.acknowledge(tenantId, incidentId, alice);
        IncidentResponse resolved = incidents.resolve(tenantId, incidentId, bob);

        assertThat(acked.status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
        assertThat(acked.assigneeId()).isEqualTo(alice);
        assertThat(resolved.status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.version()).isGreaterThan(acked.version());
        assertThat(incidents.timeline(tenantId, incidentId))
                .extracting(TimelineEventResponse::type, TimelineEventResponse::actorId)
                .containsExactly(
                        tuple(IncidentEventType.INCIDENT_CREATED, null),
                        tuple(IncidentEventType.ACKNOWLEDGED, alice),
                        tuple(IncidentEventType.ASSIGNED, alice),
                        tuple(IncidentEventType.RESOLVED, bob));
    }

    @Test
    void acknowledgeKeepsExistingAssignee() {
        incidents.assign(tenantId, incidentId, bob, alice);

        IncidentResponse acked = incidents.acknowledge(tenantId, incidentId, alice);

        assertThat(acked.assigneeId()).isEqualTo(bob);
    }

    @Test
    void reassignmentRecordsPreviousAssignee() {
        incidents.assign(tenantId, incidentId, alice, alice);
        incidents.assign(tenantId, incidentId, bob, alice);

        TimelineEventResponse last = incidents.timeline(tenantId, incidentId).getLast();
        assertThat(last.type()).isEqualTo(IncidentEventType.ASSIGNED);
        assertThat(last.details().get("assigneeId").asString()).isEqualTo(bob.toString());
        assertThat(last.details().get("previousAssigneeId").asString()).isEqualTo(alice.toString());
    }

    @Test
    void cannotAssignToUserOfAnotherTenant() {
        UUID outsider = fixtures.tenant().admin().userId();

        assertThatThrownBy(() -> incidents.assign(tenantId, incidentId, outsider, alice))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void resolvedIncidentCannotBeAcknowledgedOrReassigned() {
        incidents.resolve(tenantId, incidentId, alice);

        assertThatThrownBy(() -> incidents.acknowledge(tenantId, incidentId, alice))
                .isInstanceOf(IllegalTransitionException.class);
        assertThatThrownBy(() -> incidents.assign(tenantId, incidentId, bob, alice))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void commentsAppearInTimeline() {
        incidents.comment(tenantId, incidentId, alice, "Connection pool at 94%, rolling back deploy");

        TimelineEventResponse comment = incidents.timeline(tenantId, incidentId).getLast();
        assertThat(comment.type()).isEqualTo(IncidentEventType.COMMENT);
        assertThat(comment.actorId()).isEqualTo(alice);
        assertThat(comment.details().get("body").asString()).isEqualTo("Connection pool at 94%, rolling back deploy");
    }

    @Test
    void concurrentAcknowledgeHasExactlyOneWinner() throws Exception {
        List<UUID> responders = List.of(alice, bob);
        List<Future<IncidentResponse>> results = Concurrently.run(2,
                i -> () -> incidents.acknowledge(tenantId, incidentId, responders.get(i)));

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
        UUID otherTenant = fixtures.tenant().tenantId();

        assertThatThrownBy(() -> incidents.get(otherTenant, incidentId))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
