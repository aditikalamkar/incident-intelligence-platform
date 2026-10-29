package com.opspilot.incident;

import com.opspilot.common.ResourceNotFoundException;
import com.opspilot.common.outbox.OutboxWriter;
import com.opspilot.identity.UserService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IncidentService {

    private final IncidentRepository incidents;
    private final IncidentTimeline timeline;
    private final UserService users;
    private final OutboxWriter outbox;
    private final IncidentMetrics metrics;
    private final IncidentSearch search;

    public IncidentService(IncidentRepository incidents, IncidentTimeline timeline, UserService users,
            OutboxWriter outbox, IncidentMetrics metrics, IncidentSearch search) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.users = users;
        this.outbox = outbox;
        this.metrics = metrics;
        this.search = search;
    }

    public IncidentPage search(UUID tenantId, IncidentQuery query) {
        return search.search(tenantId, query);
    }

    public IncidentResponse get(UUID tenantId, UUID incidentId) {
        return IncidentResponse.from(load(tenantId, incidentId));
    }

    public List<TimelineEventResponse> timeline(UUID tenantId, UUID incidentId) {
        load(tenantId, incidentId);
        return timeline.list(tenantId, incidentId);
    }

    @Transactional
    public IncidentResponse acknowledge(UUID tenantId, UUID incidentId, UUID actorId) {
        Incident incident = load(tenantId, incidentId);
        UUID previousAssignee = incident.getAssigneeId();
        incident.acknowledge(actorId, Instant.now());
        incidents.flush();
        timeline.record(tenantId, incidentId, IncidentEventType.ACKNOWLEDGED, actorId, Map.of());
        if (previousAssignee == null) {
            timeline.record(tenantId, incidentId, IncidentEventType.ASSIGNED, actorId,
                    assignment(incident.getAssigneeId(), null));
        }
        outbox.append(tenantId, incidentId, IncidentEvents.ACKNOWLEDGED,
                new IncidentEvents.Acknowledged(incidentId, actorId));
        metrics.acknowledged(incident);
        return IncidentResponse.from(incident);
    }

    @Transactional
    public IncidentResponse assign(UUID tenantId, UUID incidentId, UUID assigneeId, UUID actorId) {
        if (!users.existsInTenant(tenantId, assigneeId)) {
            throw new ResourceNotFoundException("User " + assigneeId + " not found");
        }
        Incident incident = load(tenantId, incidentId);
        UUID previousAssignee = incident.getAssigneeId();
        if (assigneeId.equals(previousAssignee)) {
            return IncidentResponse.from(incident);
        }
        incident.assignTo(assigneeId);
        incidents.flush();
        timeline.record(tenantId, incidentId, IncidentEventType.ASSIGNED, actorId,
                assignment(assigneeId, previousAssignee));
        return IncidentResponse.from(incident);
    }

    @Transactional
    public IncidentResponse resolve(UUID tenantId, UUID incidentId, UUID actorId) {
        Incident incident = load(tenantId, incidentId);
        incident.resolve(Instant.now());
        incidents.flush();
        timeline.record(tenantId, incidentId, IncidentEventType.RESOLVED, actorId, Map.of());
        outbox.append(tenantId, incidentId, IncidentEvents.RESOLVED, new IncidentEvents.Resolved(incidentId, actorId));
        metrics.resolved(incident, false);
        return IncidentResponse.from(incident);
    }

    /** Comments are allowed after resolution too, for follow-up and postmortem notes. */
    @Transactional
    public TimelineEventResponse comment(UUID tenantId, UUID incidentId, UUID actorId, String body) {
        load(tenantId, incidentId);
        return timeline.record(tenantId, incidentId, IncidentEventType.COMMENT, actorId, Map.of("body", body));
    }

    /**
     * For the escalation worker. The shared row lock makes a concurrent acknowledge wait until the
     * worker's transaction ends, so an incident cannot be acknowledged between "still unacknowledged?"
     * and "page the next level".
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IncidentResponse lockForEscalation(UUID tenantId, UUID incidentId) {
        return incidents.findForShare(incidentId, tenantId)
                .map(IncidentResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Incident " + incidentId + " not found"));
    }

    /**
     * Records that one of the incident's alerts cleared, and resolves the incident if
     * {@code resolveIncident} says so. That check runs while holding the incident's row lock:
     * when the last two alerts clear concurrently, the second transaction waits, then sees the
     * first one's resolution, so the incident cannot be left open with nothing firing.
     *
     * @return true if the incident was resolved by this call
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordAlertResolved(UUID tenantId, UUID incidentId, UUID alertId, BooleanSupplier resolveIncident) {
        Incident incident = incidents.findForUpdate(incidentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident " + incidentId + " not found"));
        timeline.record(tenantId, incidentId, IncidentEventType.ALERT_RESOLVED, null, Map.of("alertId", alertId));
        if (incident.getStatus() == IncidentStatus.RESOLVED || !resolveIncident.getAsBoolean()) {
            return false;
        }
        incident.resolve(Instant.now());
        incidents.flush();
        timeline.record(tenantId, incidentId, IncidentEventType.RESOLVED, null, Map.of("reason", "ALL_ALERTS_RESOLVED"));
        outbox.append(tenantId, incidentId, IncidentEvents.RESOLVED, new IncidentEvents.Resolved(incidentId, null));
        metrics.resolved(incident, true);
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEscalation(UUID tenantId, UUID incidentId, int level, List<UUID> notifiedUserIds) {
        timeline.record(tenantId, incidentId, IncidentEventType.ESCALATED, null,
                Map.of("level", level, "notifiedUserIds", notifiedUserIds));
    }

    /** Puts a finished AI investigation on the timeline, which also tells live clients to show it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInvestigation(UUID tenantId, UUID incidentId, UUID requestedBy, UUID investigationId,
            boolean completed) {
        timeline.record(tenantId, incidentId,
                completed ? IncidentEventType.INVESTIGATION_COMPLETED : IncidentEventType.INVESTIGATION_FAILED,
                requestedBy, Map.of("investigationId", investigationId));
    }

    private static Map<String, Object> assignment(UUID assigneeId, UUID previousAssigneeId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("assigneeId", assigneeId);
        if (previousAssigneeId != null) {
            details.put("previousAssigneeId", previousAssigneeId);
        }
        return details;
    }

    private Incident load(UUID tenantId, UUID incidentId) {
        return incidents.findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident " + incidentId + " not found"));
    }
}
