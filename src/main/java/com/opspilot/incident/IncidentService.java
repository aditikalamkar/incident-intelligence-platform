package com.opspilot.incident;

import com.opspilot.common.ResourceNotFoundException;
import com.opspilot.identity.UserService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class IncidentService {

    private final IncidentRepository incidents;
    private final IncidentTimeline timeline;
    private final UserService users;

    public IncidentService(IncidentRepository incidents, IncidentTimeline timeline, UserService users) {
        this.incidents = incidents;
        this.timeline = timeline;
        this.users = users;
    }

    public List<IncidentResponse> list(UUID tenantId, IncidentStatus status, int limit) {
        Limit max = Limit.of(limit);
        List<Incident> found = status == null
                ? incidents.findByTenantIdOrderByOpenedAtDesc(tenantId, max)
                : incidents.findByTenantIdAndStatusOrderByOpenedAtDesc(tenantId, status, max);
        return found.stream().map(IncidentResponse::from).toList();
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
        return IncidentResponse.from(incident);
    }

    /** Comments are allowed after resolution too, for follow-up and postmortem notes. */
    @Transactional
    public TimelineEventResponse comment(UUID tenantId, UUID incidentId, UUID actorId, String body) {
        load(tenantId, incidentId);
        return timeline.record(tenantId, incidentId, IncidentEventType.COMMENT, actorId, Map.of("body", body));
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
