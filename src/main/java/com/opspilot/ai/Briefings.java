package com.opspilot.ai;

import com.opspilot.ai.Briefing.Facts;
import com.opspilot.ai.Briefing.PastIncident;
import com.opspilot.alerting.IncidentAlerts;
import com.opspilot.identity.UserResponse;
import com.opspilot.identity.UserService;
import com.opspilot.incident.IncidentEventType;
import com.opspilot.incident.IncidentQuery;
import com.opspilot.incident.IncidentResponse;
import com.opspilot.incident.IncidentService;
import com.opspilot.incident.TimelineEventResponse;
import com.opspilot.servicecatalog.ServiceCatalog;
import com.opspilot.servicecatalog.ServiceResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Gathers what the assistant is told about an incident from the modules that own it. */
@Component
class Briefings {

    static final int MAX_ALERTS = 20;
    static final int MAX_PAST_INCIDENTS = 5;
    static final int MAX_PAST_COMMENTS = 3;

    private final IncidentService incidents;
    private final IncidentAlerts alerts;
    private final UserService users;
    private final ServiceCatalog services;
    private final JsonMapper json;

    Briefings(IncidentService incidents, IncidentAlerts alerts, UserService users, ServiceCatalog services,
            JsonMapper json) {
        this.incidents = incidents;
        this.alerts = alerts;
        this.users = users;
        this.services = services;
        this.json = json;
    }

    Briefing prepare(UUID tenantId, UUID incidentId) {
        Instant now = Instant.now();
        IncidentResponse incident = incidents.get(tenantId, incidentId);
        Map<UUID, String> userNames = users.list(tenantId).stream()
                .collect(Collectors.toMap(UserResponse::id, UserResponse::name));
        String serviceName = services.list(tenantId).stream()
                .filter(service -> service.id().equals(incident.serviceId()))
                .map(ServiceResponse::name)
                .findFirst()
                .orElse(null);
        return Briefing.of(new Facts(incident, serviceName, userNames,
                alerts.latest(tenantId, incidentId, MAX_ALERTS),
                incidents.timeline(tenantId, incidentId),
                pastIncidents(tenantId, incident),
                now), json);
    }

    /** The service's incidents opened in the {@link Briefing#HISTORY} before this one, newest first. */
    private List<PastIncident> pastIncidents(UUID tenantId, IncidentResponse incident) {
        if (incident.serviceId() == null) {
            return List.of();
        }
        List<IncidentResponse> past = incidents.search(tenantId, new IncidentQuery(null, null,
                Set.of(incident.serviceId()), null, false, incident.openedAt().minus(Briefing.HISTORY),
                incident.openedAt(), null, null, MAX_PAST_INCIDENTS)).items();
        return past.stream()
                .map(earlier -> new PastIncident(earlier, lastComments(tenantId, earlier.id())))
                .toList();
    }

    private List<TimelineEventResponse> lastComments(UUID tenantId, UUID incidentId) {
        List<TimelineEventResponse> comments = incidents.timeline(tenantId, incidentId).stream()
                .filter(event -> event.type() == IncidentEventType.COMMENT)
                .toList();
        return comments.subList(Math.max(0, comments.size() - MAX_PAST_COMMENTS), comments.size());
    }
}
