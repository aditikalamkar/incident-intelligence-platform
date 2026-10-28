package com.opspilot.incident;

import com.opspilot.common.outbox.OutboxWriter;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Entry point used by the alerting module to attach an alert to an incident. */
@Component
public class IncidentIngestion {

    public record AlertSignal(UUID tenantId, UUID serviceId, UUID alertId, String fingerprint, String title,
            Severity severity) {
    }

    public record Result(UUID incidentId, boolean created) {
    }

    private final JdbcClient jdbc;
    private final IncidentTimeline timeline;
    private final OutboxWriter outbox;
    private final IncidentMetrics metrics;

    public IncidentIngestion(JdbcClient jdbc, IncidentTimeline timeline, OutboxWriter outbox,
            IncidentMetrics metrics) {
        this.jdbc = jdbc;
        this.timeline = timeline;
        this.outbox = outbox;
        this.metrics = metrics;
    }

    /**
     * Opens a new incident or groups the alert into the open incident with the same fingerprint.
     * The upsert targets the partial unique index on open incidents, so concurrent alerts with the
     * same fingerprint can never create two open incidents. {@code version} is deliberately not
     * bumped here: a new alert must not make an engineer's acknowledge fail with a conflict.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result recordAlert(AlertSignal signal) {
        UUID newId = UUID.randomUUID();
        UUID incidentId = jdbc.sql("""
                INSERT INTO incident (id, tenant_id, service_id, fingerprint, title, severity, status)
                VALUES (:id, :tenantId, :serviceId, :fingerprint, :title, :severity, 'TRIGGERED')
                ON CONFLICT (tenant_id, fingerprint) WHERE status <> 'RESOLVED'
                DO UPDATE SET alert_count = incident.alert_count + 1
                RETURNING id
                """)
                .param("id", newId)
                .param("tenantId", signal.tenantId())
                .param("serviceId", signal.serviceId())
                .param("fingerprint", signal.fingerprint())
                .param("title", signal.title())
                .param("severity", signal.severity().name())
                .query(UUID.class)
                .single();

        boolean created = incidentId.equals(newId);
        timeline.record(signal.tenantId(), incidentId,
                created ? IncidentEventType.INCIDENT_CREATED : IncidentEventType.ALERT_GROUPED,
                null, Map.of("alertId", signal.alertId()));
        if (created) {
            outbox.append(signal.tenantId(), incidentId, IncidentEvents.OPENED,
                    new IncidentEvents.Opened(incidentId, signal.serviceId(), signal.title(), signal.severity()));
            metrics.opened(signal.severity());
        }
        return new Result(incidentId, created);
    }
}
