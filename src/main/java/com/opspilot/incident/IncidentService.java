package com.opspilot.incident;

import com.opspilot.common.ResourceNotFoundException;
import java.time.Instant;
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

    public IncidentService(IncidentRepository incidents, IncidentTimeline timeline) {
        this.incidents = incidents;
        this.timeline = timeline;
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
    public IncidentResponse acknowledge(UUID tenantId, UUID incidentId) {
        Incident incident = load(tenantId, incidentId);
        incident.acknowledge(Instant.now());
        return saveTransition(incident, IncidentEventType.ACKNOWLEDGED);
    }

    @Transactional
    public IncidentResponse resolve(UUID tenantId, UUID incidentId) {
        Incident incident = load(tenantId, incidentId);
        incident.resolve(Instant.now());
        return saveTransition(incident, IncidentEventType.RESOLVED);
    }

    /** Flushing here surfaces an optimistic-lock conflict before the timeline event is written. */
    private IncidentResponse saveTransition(Incident incident, IncidentEventType type) {
        incidents.flush();
        timeline.record(incident.getTenantId(), incident.getId(), type, null, Map.of());
        return IncidentResponse.from(incident);
    }

    private Incident load(UUID tenantId, UUID incidentId) {
        return incidents.findByIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Incident " + incidentId + " not found"));
    }
}
