package com.opspilot.incident;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        UUID serviceId,
        String title,
        Severity severity,
        IncidentStatus status,
        UUID assigneeId,
        int alertCount,
        long version,
        Instant openedAt,
        Instant acknowledgedAt,
        Instant resolvedAt) {

    static IncidentResponse from(Incident incident) {
        return new IncidentResponse(
                incident.getId(),
                incident.getServiceId(),
                incident.getTitle(),
                incident.getSeverity(),
                incident.getStatus(),
                incident.getAssigneeId(),
                incident.getAlertCount(),
                incident.getVersion(),
                incident.getOpenedAt(),
                incident.getAcknowledgedAt(),
                incident.getResolvedAt());
    }
}
