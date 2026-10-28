package com.opspilot.alerting;

import com.opspilot.alerting.IngestionResult.Outcome;
import com.opspilot.common.Hashing;
import com.opspilot.incident.IncidentIngestion;
import com.opspilot.incident.IncidentIngestion.AlertSignal;
import com.opspilot.incident.IncidentService;
import com.opspilot.incident.Severity;
import com.opspilot.servicecatalog.ServiceCatalog;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AlertIngestionService {

    private record AlertRef(UUID alertId, UUID incidentId) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ServiceCatalog services;
    private final IncidentIngestion incidents;
    private final IncidentService incidentService;

    public AlertIngestionService(JdbcClient jdbc, JsonMapper json, ServiceCatalog services,
            IncidentIngestion incidents, IncidentService incidentService) {
        this.jdbc = jdbc;
        this.json = json;
        this.services = services;
        this.incidents = incidents;
        this.incidentService = incidentService;
    }

    @Transactional
    public IngestionResult ingest(UUID tenantId, String source, GenericAlertRequest request) {
        return ingest(tenantId, source, request, request);
    }

    /**
     * Idempotent: the alert insert is the gate. If another request (a retry, possibly concurrent)
     * already stored this event, the insert is skipped and the original result is returned, so the
     * incident is never touched twice for the same event.
     *
     * @param rawPayload stored as the alert's evidence, e.g. the original Alertmanager alert with its links
     */
    @Transactional
    public IngestionResult ingest(UUID tenantId, String source, GenericAlertRequest request, Object rawPayload) {
        String fingerprint = request.fingerprint() != null && !request.fingerprint().isBlank()
                ? request.fingerprint()
                : Hashing.sha256Hex(source + '|' + request.service() + '|' + request.alertName());

        UUID alertId = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO alert (id, tenant_id, source, source_event_id, fingerprint, payload)
                VALUES (:id, :tenantId, :source, :eventId, :fingerprint, CAST(:payload AS jsonb))
                ON CONFLICT (tenant_id, source, source_event_id) DO NOTHING
                """)
                .param("id", alertId)
                .param("tenantId", tenantId)
                .param("source", source)
                .param("eventId", request.eventId())
                .param("fingerprint", fingerprint)
                .param("payload", json.writeValueAsString(rawPayload))
                .update();

        if (inserted == 0) {
            return duplicate(tenantId, source, request.eventId()).orElseThrow();
        }

        UUID serviceId = services.resolveOrRegister(tenantId, request.service());
        Severity severity = request.severity() != null ? request.severity() : Severity.MEDIUM;
        String title = request.alertName() + " on " + request.service();
        IncidentIngestion.Result incident = incidents.recordAlert(
                new AlertSignal(tenantId, serviceId, alertId, fingerprint, title, severity));

        jdbc.sql("UPDATE alert SET incident_id = :incidentId WHERE id = :id")
                .param("incidentId", incident.incidentId())
                .param("id", alertId)
                .update();

        return new IngestionResult(alertId, incident.incidentId(),
                incident.created() ? Outcome.CREATED_INCIDENT : Outcome.GROUPED);
    }

    /**
     * Marks a previously received alert as cleared. Idempotent: a repeated resolution is reported as
     * a duplicate. With {@code autoResolveIncident}, the incident is resolved once none of its alerts
     * is still firing.
     *
     * @param eventId the same event ID the alert was received with while firing
     */
    @Transactional
    public IngestionResult resolve(UUID tenantId, String source, String eventId, boolean autoResolveIncident) {
        Optional<AlertRef> cleared = jdbc.sql("""
                UPDATE alert SET resolved_at = now()
                WHERE tenant_id = :tenantId AND source = :source AND source_event_id = :eventId
                  AND resolved_at IS NULL
                RETURNING id, incident_id
                """)
                .param("tenantId", tenantId)
                .param("source", source)
                .param("eventId", eventId)
                .query((rs, row) -> new AlertRef(rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class)))
                .optional();
        if (cleared.isEmpty()) {
            return duplicate(tenantId, source, eventId)
                    .orElseGet(() -> new IngestionResult(null, null, Outcome.UNKNOWN_ALERT));
        }

        AlertRef alert = cleared.get();
        boolean incidentResolved = incidentService.recordAlertResolved(tenantId, alert.incidentId(), alert.alertId(),
                () -> autoResolveIncident && !anyAlertFiring(alert.incidentId()));
        return new IngestionResult(alert.alertId(), alert.incidentId(),
                incidentResolved ? Outcome.INCIDENT_RESOLVED : Outcome.ALERT_RESOLVED);
    }

    private boolean anyAlertFiring(UUID incidentId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM alert WHERE incident_id = :incidentId AND resolved_at IS NULL)")
                .param("incidentId", incidentId)
                .query(Boolean.class)
                .single();
    }

    private Optional<IngestionResult> duplicate(UUID tenantId, String source, String eventId) {
        return jdbc.sql("""
                SELECT id, incident_id FROM alert
                WHERE tenant_id = :tenantId AND source = :source AND source_event_id = :eventId
                """)
                .param("tenantId", tenantId)
                .param("source", source)
                .param("eventId", eventId)
                .query((rs, row) -> new IngestionResult(
                        rs.getObject("id", UUID.class),
                        rs.getObject("incident_id", UUID.class),
                        Outcome.DUPLICATE))
                .optional();
    }
}
