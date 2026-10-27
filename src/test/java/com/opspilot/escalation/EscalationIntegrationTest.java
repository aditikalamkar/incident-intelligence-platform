package com.opspilot.escalation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.opspilot.Concurrently;
import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.alerting.AlertIngestionService;
import com.opspilot.alerting.GenericAlertRequest;
import com.opspilot.common.outbox.OutboxRelay;
import com.opspilot.identity.Role;
import com.opspilot.identity.TeamService;
import com.opspilot.incident.IncidentEventType;
import com.opspilot.incident.IncidentService;
import com.opspilot.incident.Severity;
import com.opspilot.notification.NotificationDispatcher;
import com.opspilot.servicecatalog.ServiceCatalog;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Drives the relay, escalation worker and dispatcher by hand; scheduling is off in tests. */
@IntegrationTest
class EscalationIntegrationTest {

    @Autowired
    AlertIngestionService ingestion;

    @Autowired
    IncidentService incidents;

    @Autowired
    ServiceCatalog services;

    @Autowired
    TeamService teams;

    @Autowired
    EscalationPolicyService policies;

    @Autowired
    OutboxRelay relay;

    @Autowired
    EscalationWorker worker;

    @Autowired
    NotificationDispatcher dispatcher;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    JdbcClient jdbc;

    UUID tenantId;
    UUID alice;
    UUID bob;
    UUID carol;

    @BeforeEach
    void setUp() {
        tenantId = fixtures.tenant().tenantId();
        alice = fixtures.user(tenantId, Role.RESPONDER).userId();
        bob = fixtures.user(tenantId, Role.RESPONDER).userId();
        carol = fixtures.user(tenantId, Role.RESPONDER).userId();
        UUID teamId = teams.create(tenantId, "Payments").id();
        services.assignTeam(tenantId, services.resolveOrRegister(tenantId, "payment-api"), teamId);
        policies.save(tenantId, teamId, List.of(
                new EscalationStep(List.of(alice), 5),
                new EscalationStep(List.of(bob, carol), 5)));
    }

    @Test
    void pagesEachLevelInTurnWhileTheIncidentStaysUnacknowledged() {
        UUID incidentId = openIncident("payment-api");
        relayAll();
        worker.escalateDue();

        assertThat(paged(incidentId)).containsExactly(alice);
        assertThat(minutesUntilNextLevel(incidentId)).isCloseTo(5.0, within(0.1));

        makeDue(incidentId);
        worker.escalateDue();

        assertThat(paged(incidentId)).containsExactlyInAnyOrder(alice, bob, carol);
        assertThat(stopReason(incidentId)).isEqualTo("COMPLETED");
        assertThat(incidents.timeline(tenantId, incidentId))
                .filteredOn(e -> e.type() == IncidentEventType.ESCALATED).hasSize(2);

        dispatcher.dispatchPending();
        assertThat(notificationStatuses(incidentId)).containsOnly("SENT");
    }

    @Test
    void acknowledgementStopsEscalationEvenBeforeItsEventIsRelayed() {
        UUID incidentId = openIncident("payment-api");
        relayAll();
        worker.escalateDue();

        incidents.acknowledge(tenantId, incidentId, alice);
        makeDue(incidentId);
        worker.escalateDue();

        assertThat(paged(incidentId)).containsExactly(alice);
        assertThat(stopReason(incidentId)).isEqualTo("ACKNOWLEDGED");

        relayAll();
        assertThat(stopReason(incidentId)).as("the late event is a no-op").isEqualTo("ACKNOWLEDGED");
    }

    @Test
    void incidentResolvedBeforeTheFirstPageIsNeverPaged() {
        UUID incidentId = openIncident("payment-api");
        incidents.resolve(tenantId, incidentId, alice);

        relayAll();
        worker.escalateDue();

        assertThat(paged(incidentId)).isEmpty();
        assertThat(stopReason(incidentId)).isEqualTo("RESOLVED");
    }

    @Test
    void concurrentWorkersPageEachLevelExactlyOnce() throws Exception {
        UUID incidentId = openIncident("payment-api");
        relayAll();

        List<Future<Integer>> workers = Concurrently.run(5, i -> worker::escalateDue);
        for (Future<Integer> result : workers) {
            result.get();
        }

        assertThat(paged(incidentId)).containsExactly(alice);
        assertThat(incidents.timeline(tenantId, incidentId))
                .filteredOn(e -> e.type() == IncidentEventType.ESCALATED).hasSize(1);
    }

    @Test
    void incidentOfServiceWithoutOwningTeamIsNotEscalated() {
        UUID incidentId = openIncident("unowned-api");
        relayAll();

        assertThat(jdbc.sql("SELECT count(*) FROM incident_escalation WHERE incident_id = :id")
                .param("id", incidentId).query(Long.class).single()).isZero();
    }

    private UUID openIncident(String service) {
        return ingestion.ingest(tenantId, "generic", new GenericAlertRequest(
                UUID.randomUUID().toString(), service, "HighLatency", Severity.CRITICAL, null, null, null))
                .incidentId();
    }

    /** Events of one incident are relayed one per pass, in order, so keep going until nothing is left. */
    private void relayAll() {
        for (int pass = 0; pass < 20 && relay.relayPending() > 0; pass++) {
            // keep relaying
        }
    }

    private void makeDue(UUID incidentId) {
        jdbc.sql("UPDATE incident_escalation SET due_at = now() WHERE incident_id = :id")
                .param("id", incidentId)
                .update();
    }

    private List<UUID> paged(UUID incidentId) {
        return jdbc.sql("SELECT user_id FROM notification WHERE incident_id = :id")
                .param("id", incidentId)
                .query(UUID.class)
                .list();
    }

    private List<String> notificationStatuses(UUID incidentId) {
        return jdbc.sql("SELECT status FROM notification WHERE incident_id = :id")
                .param("id", incidentId)
                .query(String.class)
                .list();
    }

    private double minutesUntilNextLevel(UUID incidentId) {
        return jdbc.sql("SELECT EXTRACT(EPOCH FROM due_at - now()) / 60 FROM incident_escalation WHERE incident_id = :id")
                .param("id", incidentId)
                .query(Double.class)
                .single();
    }

    private String stopReason(UUID incidentId) {
        return jdbc.sql("SELECT stop_reason FROM incident_escalation WHERE incident_id = :id")
                .param("id", incidentId)
                .query(String.class)
                .single();
    }
}
