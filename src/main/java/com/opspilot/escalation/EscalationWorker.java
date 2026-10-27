package com.opspilot.escalation;

import com.opspilot.common.WorkerProperties;
import com.opspilot.incident.IncidentResponse;
import com.opspilot.incident.IncidentService;
import com.opspilot.incident.IncidentStatus;
import com.opspilot.notification.NotificationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pages the next escalation level for incidents that are due. The schedule lives in Postgres, so
 * work that came due while the application was down is picked up on the next poll after a restart.
 * Due times are compared with the database clock, so instances with skewed clocks agree.
 */
@Component
public class EscalationWorker {

    private static final Logger log = LoggerFactory.getLogger(EscalationWorker.class);
    private static final int RETRY_AFTER_MINUTES = 1;

    private record DueEscalation(UUID incidentId, UUID tenantId, UUID teamId, int nextStep) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Escalations escalations;
    private final EscalationPolicyService policies;
    private final IncidentService incidents;
    private final NotificationService notifications;
    private final WorkerProperties properties;

    public EscalationWorker(JdbcClient jdbc, TransactionTemplate tx, Escalations escalations,
            EscalationPolicyService policies, IncidentService incidents, NotificationService notifications,
            WorkerProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.escalations = escalations;
        this.policies = policies;
        this.incidents = incidents;
        this.notifications = notifications;
        this.properties = properties;
    }

    /** @return the number of escalation levels paged */
    @Scheduled(fixedDelayString = "${opspilot.workers.poll-interval:PT1S}")
    public int escalateDue() {
        List<UUID> due = jdbc.sql("""
                SELECT incident_id FROM incident_escalation
                WHERE stopped_at IS NULL AND due_at <= now()
                ORDER BY due_at LIMIT :limit
                """)
                .param("limit", properties.batchSize())
                .query(UUID.class)
                .list();
        int paged = 0;
        for (UUID incidentId : due) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> escalate(incidentId)))) {
                    paged++;
                }
            } catch (RuntimeException e) {
                log.warn("Escalation of incident {} failed; retrying in {} minute", incidentId, RETRY_AFTER_MINUTES, e);
                tx.executeWithoutResult(status -> escalations.postpone(incidentId, RETRY_AFTER_MINUTES));
            }
        }
        return paged;
    }

    private boolean escalate(UUID incidentId) {
        Optional<DueEscalation> claimed = jdbc.sql("""
                SELECT incident_id, tenant_id, team_id, next_step FROM incident_escalation
                WHERE incident_id = :incidentId AND stopped_at IS NULL AND due_at <= now()
                FOR UPDATE SKIP LOCKED
                """)
                .param("incidentId", incidentId)
                .query((rs, row) -> new DueEscalation(
                        rs.getObject("incident_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("team_id", UUID.class),
                        rs.getInt("next_step")))
                .optional();
        if (claimed.isEmpty()) {
            return false; // another instance is paging it, or it was stopped or rescheduled meanwhile
        }
        DueEscalation escalation = claimed.get();

        // Re-check the incident itself: its "acknowledged" event may not have been relayed yet.
        IncidentResponse incident = incidents.lockForEscalation(escalation.tenantId(), incidentId);
        if (incident.status() != IncidentStatus.TRIGGERED) {
            escalations.stop(incidentId, incident.status().name());
            return false;
        }
        List<EscalationStep> steps = policies.steps(escalation.tenantId(), escalation.teamId());
        if (escalation.nextStep() >= steps.size()) {
            escalations.stop(incidentId, "COMPLETED");
            return false;
        }

        EscalationStep step = steps.get(escalation.nextStep());
        int level = escalation.nextStep() + 1;
        String subject = "[%s] %s".formatted(incident.severity(), incident.title());
        notifications.page(escalation.tenantId(), incidentId, escalation.teamId(), step.userIds(), subject, level);
        incidents.recordEscalation(escalation.tenantId(), incidentId, level, step.userIds());
        if (level < steps.size()) {
            escalations.advance(incidentId, level, step.escalateAfterMinutes());
        } else {
            escalations.stop(incidentId, "COMPLETED");
        }
        return true;
    }
}
