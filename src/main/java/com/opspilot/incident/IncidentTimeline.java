package com.opspilot.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Every change to an incident is recorded here, which also makes this the one place that announces changes. */
@Component
public class IncidentTimeline {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public IncidentTimeline(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Uses clock_timestamp() rather than the column default now(): now() is fixed for the whole
     * transaction, which would give events written together the same time and an unstable order.
     * The change notification joins the caller's transaction, so live streams see it only after commit.
     */
    TimelineEventResponse record(UUID tenantId, UUID incidentId, IncidentEventType type, UUID actorId,
            Map<String, ?> details) {
        UUID id = UUID.randomUUID();
        Instant createdAt = jdbc.sql("""
                INSERT INTO incident_event (id, tenant_id, incident_id, type, actor_id, details, created_at)
                VALUES (:id, :tenantId, :incidentId, :type, :actorId, CAST(:details AS jsonb), clock_timestamp())
                RETURNING created_at
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("incidentId", incidentId)
                .param("type", type.name())
                .param("actorId", actorId)
                .param("details", json.writeValueAsString(details))
                .query((rs, row) -> rs.getTimestamp("created_at").toInstant())
                .single();
        jdbc.sql("SELECT pg_notify(:channel, :payload)")
                .param("channel", IncidentChange.CHANNEL)
                .param("payload", json.writeValueAsString(new IncidentChange(tenantId, incidentId, id)))
                .query()
                .listOfRows();
        return new TimelineEventResponse(id, type, actorId, json.valueToTree(details), createdAt);
    }

    List<TimelineEventResponse> list(UUID tenantId, UUID incidentId) {
        return jdbc.sql("""
                SELECT id, type, actor_id, details, created_at FROM incident_event
                WHERE tenant_id = :tenantId AND incident_id = :incidentId
                ORDER BY created_at, id
                """)
                .param("tenantId", tenantId)
                .param("incidentId", incidentId)
                .query((rs, row) -> toResponse(rs))
                .list();
    }

    Optional<TimelineEventResponse> find(UUID tenantId, UUID eventId) {
        return jdbc.sql("""
                SELECT id, type, actor_id, details, created_at FROM incident_event
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", eventId)
                .query((rs, row) -> toResponse(rs))
                .optional();
    }

    private TimelineEventResponse toResponse(ResultSet rs) throws SQLException {
        String details = rs.getString("details");
        return new TimelineEventResponse(
                rs.getObject("id", UUID.class),
                IncidentEventType.valueOf(rs.getString("type")),
                rs.getObject("actor_id", UUID.class),
                details == null ? null : json.readTree(details),
                rs.getTimestamp("created_at").toInstant());
    }
}
