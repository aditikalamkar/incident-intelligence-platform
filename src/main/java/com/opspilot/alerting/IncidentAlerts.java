package com.opspilot.alerting;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The alerts behind an incident, for other modules. */
@Component
public class IncidentAlerts {

    /**
     * @param payload    the alert exactly as the monitoring system sent it
     * @param resolvedAt when the monitoring system reported it cleared; null while firing
     */
    public record AlertRecord(UUID id, String source, JsonNode payload, Instant receivedAt, Instant resolvedAt) {
    }

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public IncidentAlerts(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The incident's most recent alerts, oldest first. */
    public List<AlertRecord> latest(UUID tenantId, UUID incidentId, int limit) {
        return jdbc.sql("""
                SELECT * FROM (
                    SELECT id, source, payload, received_at, resolved_at FROM alert
                    WHERE tenant_id = :tenantId AND incident_id = :incidentId
                    ORDER BY received_at DESC, id DESC
                    LIMIT :limit
                ) latest
                ORDER BY received_at, id
                """)
                .param("tenantId", tenantId)
                .param("incidentId", incidentId)
                .param("limit", limit)
                .query((rs, row) -> new AlertRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("source"),
                        json.readTree(rs.getString("payload")),
                        rs.getTimestamp("received_at").toInstant(),
                        toInstant(rs.getTimestamp("resolved_at"))))
                .list();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
