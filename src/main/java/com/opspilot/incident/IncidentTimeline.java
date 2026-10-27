package com.opspilot.incident;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class IncidentTimeline {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public IncidentTimeline(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void record(UUID tenantId, UUID incidentId, IncidentEventType type, UUID actorId, Map<String, ?> details) {
        jdbc.sql("""
                INSERT INTO incident_event (id, tenant_id, incident_id, type, actor_id, details)
                VALUES (:id, :tenantId, :incidentId, :type, :actorId, CAST(:details AS jsonb))
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("incidentId", incidentId)
                .param("type", type.name())
                .param("actorId", actorId)
                .param("details", json.writeValueAsString(details))
                .update();
    }

    List<TimelineEventResponse> list(UUID tenantId, UUID incidentId) {
        return jdbc.sql("""
                SELECT id, type, actor_id, details, created_at FROM incident_event
                WHERE tenant_id = :tenantId AND incident_id = :incidentId
                ORDER BY created_at, id
                """)
                .param("tenantId", tenantId)
                .param("incidentId", incidentId)
                .query((rs, row) -> {
                    String details = rs.getString("details");
                    return new TimelineEventResponse(
                            rs.getObject("id", UUID.class),
                            IncidentEventType.valueOf(rs.getString("type")),
                            rs.getObject("actor_id", UUID.class),
                            details == null ? null : json.readTree(details),
                            rs.getTimestamp("created_at").toInstant());
                })
                .list();
    }
}
