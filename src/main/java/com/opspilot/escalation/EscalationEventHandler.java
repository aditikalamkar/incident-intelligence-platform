package com.opspilot.escalation;

import com.opspilot.common.outbox.OutboxEvent;
import com.opspilot.common.outbox.OutboxEventHandler;
import com.opspilot.incident.IncidentEvents;
import com.opspilot.servicecatalog.ServiceCatalog;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Starts an escalation when an incident opens and stops it when the incident is acknowledged or resolved. */
@Component
class EscalationEventHandler implements OutboxEventHandler {

    private final Escalations escalations;
    private final EscalationPolicyService policies;
    private final ServiceCatalog services;
    private final JsonMapper json;

    EscalationEventHandler(Escalations escalations, EscalationPolicyService policies, ServiceCatalog services,
            JsonMapper json) {
        this.escalations = escalations;
        this.policies = policies;
        this.services = services;
        this.json = json;
    }

    @Override
    public boolean handles(String type) {
        return switch (type) {
            case IncidentEvents.OPENED, IncidentEvents.ACKNOWLEDGED, IncidentEvents.RESOLVED -> true;
            default -> false;
        };
    }

    @Override
    public void handle(OutboxEvent event) {
        switch (event.type()) {
            case IncidentEvents.OPENED -> start(event);
            case IncidentEvents.ACKNOWLEDGED -> escalations.stop(event.aggregateId(), "ACKNOWLEDGED");
            case IncidentEvents.RESOLVED -> escalations.stop(event.aggregateId(), "RESOLVED");
            default -> throw new IllegalArgumentException("Unexpected event type " + event.type());
        }
    }

    /** Incidents of services without an owning team, or teams without a policy, are not escalated. */
    private void start(OutboxEvent event) {
        IncidentEvents.Opened opened = json.readValue(event.payload(), IncidentEvents.Opened.class);
        Optional<UUID> team = services.owningTeam(event.tenantId(), opened.serviceId());
        if (team.isPresent() && !policies.steps(event.tenantId(), team.get()).isEmpty()) {
            escalations.start(event.tenantId(), opened.incidentId(), team.get());
        }
    }
}
