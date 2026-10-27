package com.opspilot.escalation;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The durable escalation schedule ({@code incident_escalation}). All operations are idempotent. */
@Component
class Escalations {

    private final JdbcClient jdbc;

    Escalations(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Schedules the first level immediately. Does nothing if this incident was ever scheduled. */
    void start(UUID tenantId, UUID incidentId, UUID teamId) {
        jdbc.sql("""
                INSERT INTO incident_escalation (incident_id, tenant_id, team_id, next_step, due_at)
                VALUES (:incidentId, :tenantId, :teamId, 0, now())
                ON CONFLICT (incident_id) DO NOTHING
                """)
                .param("incidentId", incidentId)
                .param("tenantId", tenantId)
                .param("teamId", teamId)
                .update();
    }

    void advance(UUID incidentId, int nextStep, int afterMinutes) {
        jdbc.sql("""
                UPDATE incident_escalation SET next_step = :nextStep, due_at = now() + make_interval(mins => :minutes)
                WHERE incident_id = :incidentId
                """)
                .param("nextStep", nextStep)
                .param("minutes", afterMinutes)
                .param("incidentId", incidentId)
                .update();
    }

    void stop(UUID incidentId, String reason) {
        jdbc.sql("""
                UPDATE incident_escalation SET stopped_at = now(), stop_reason = :reason
                WHERE incident_id = :incidentId AND stopped_at IS NULL
                """)
                .param("reason", reason)
                .param("incidentId", incidentId)
                .update();
    }

    /** Pushes failing work back so one broken escalation cannot be retried on every poll. */
    void postpone(UUID incidentId, int minutes) {
        jdbc.sql("UPDATE incident_escalation SET due_at = now() + make_interval(mins => :minutes) WHERE incident_id = :incidentId")
                .param("minutes", minutes)
                .param("incidentId", incidentId)
                .update();
    }
}
