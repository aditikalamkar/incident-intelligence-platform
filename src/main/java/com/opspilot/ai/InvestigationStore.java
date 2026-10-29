package com.opspilot.ai;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class InvestigationStore {

    private static final String COLUMNS = """
            id, incident_id, status, requested_by, model, created_at, completed_at, result, failure
            """;

    private final JdbcClient jdbc;
    private final JsonMapper json;

    InvestigationStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @return false if the incident already has a running investigation. The unique index on running
     *         investigations decides, so two requests at once cannot both start one.
     */
    boolean insertRunning(UUID id, UUID tenantId, UUID incidentId, UUID requestedBy) {
        return jdbc.sql("""
                INSERT INTO investigation (id, tenant_id, incident_id, requested_by, status)
                VALUES (:id, :tenantId, :incidentId, :requestedBy, 'RUNNING')
                ON CONFLICT (incident_id) WHERE status = 'RUNNING' DO NOTHING
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("incidentId", incidentId)
                .param("requestedBy", requestedBy)
                .update() == 1;
    }

    Optional<InvestigationResponse> running(UUID tenantId, UUID incidentId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM investigation
                WHERE tenant_id = :tenantId AND incident_id = :incidentId AND status = 'RUNNING'
                """)
                .param("tenantId", tenantId)
                .param("incidentId", incidentId)
                .query((rs, row) -> toResponse(rs))
                .optional();
    }

    Optional<InvestigationResponse> latest(UUID tenantId, UUID incidentId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM investigation
                WHERE tenant_id = :tenantId AND incident_id = :incidentId
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("incidentId", incidentId)
                .query((rs, row) -> toResponse(rs))
                .optional();
    }

    int countSince(UUID tenantId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM investigation WHERE tenant_id = :tenantId AND created_at >= :since")
                .param("tenantId", tenantId)
                .param("since", Timestamp.from(since))
                .query(Integer.class)
                .single();
    }

    /** @return false if the investigation is no longer running, e.g. it was given up as interrupted */
    boolean complete(UUID id, InvestigationResult result, String model, long inputTokens, long outputTokens) {
        return jdbc.sql("""
                UPDATE investigation
                SET status = 'COMPLETED', result = CAST(:result AS jsonb), model = :model,
                    input_tokens = :inputTokens, output_tokens = :outputTokens, completed_at = now()
                WHERE id = :id AND status = 'RUNNING'
                """)
                .param("id", id)
                .param("result", json.writeValueAsString(result))
                .param("model", model)
                .param("inputTokens", inputTokens)
                .param("outputTokens", outputTokens)
                .update() == 1;
    }

    /** @return false if the investigation is no longer running */
    boolean fail(UUID id, String failure) {
        return jdbc.sql("""
                UPDATE investigation SET status = 'FAILED', failure = :failure, completed_at = now()
                WHERE id = :id AND status = 'RUNNING'
                """)
                .param("id", id)
                .param("failure", failure)
                .update() == 1;
    }

    /** Gives up on the incident's investigation if it has been running since before {@code cutoff}. */
    void failStale(UUID tenantId, UUID incidentId, Instant cutoff, String failure) {
        jdbc.sql("""
                UPDATE investigation SET status = 'FAILED', failure = :failure, completed_at = now()
                WHERE tenant_id = :tenantId AND incident_id = :incidentId AND status = 'RUNNING'
                  AND created_at < :cutoff
                """)
                .param("tenantId", tenantId)
                .param("incidentId", incidentId)
                .param("cutoff", Timestamp.from(cutoff))
                .param("failure", failure)
                .update();
    }

    private InvestigationResponse toResponse(ResultSet rs) throws SQLException {
        String result = rs.getString("result");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new InvestigationResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("incident_id", UUID.class),
                InvestigationStatus.valueOf(rs.getString("status")),
                rs.getObject("requested_by", UUID.class),
                rs.getString("model"),
                rs.getTimestamp("created_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                result == null ? null : json.readValue(result, InvestigationResult.class),
                rs.getString("failure"));
    }
}
