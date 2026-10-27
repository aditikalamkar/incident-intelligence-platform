package com.opspilot.escalation;

import com.opspilot.common.ResourceNotFoundException;
import com.opspilot.identity.TeamService;
import com.opspilot.identity.UserService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class EscalationPolicyService {

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TeamService teams;
    private final UserService users;

    public EscalationPolicyService(JdbcClient jdbc, JsonMapper json, TeamService teams, UserService users) {
        this.jdbc = jdbc;
        this.json = json;
        this.teams = teams;
        this.users = users;
    }

    /** Replaces the team's policy. Escalations already in progress follow the new steps from their current level. */
    public EscalationPolicyResponse save(UUID tenantId, UUID teamId, List<EscalationStep> steps) {
        if (!teams.exists(tenantId, teamId)) {
            throw new ResourceNotFoundException("Team " + teamId + " not found");
        }
        steps.stream().flatMap(step -> step.userIds().stream()).distinct().forEach(userId -> {
            if (!users.existsInTenant(tenantId, userId)) {
                throw new ResourceNotFoundException("User " + userId + " not found");
            }
        });
        Instant updatedAt = jdbc.sql("""
                INSERT INTO escalation_policy (id, tenant_id, team_id, steps)
                VALUES (:id, :tenantId, :teamId, CAST(:steps AS jsonb))
                ON CONFLICT (team_id) DO UPDATE SET steps = EXCLUDED.steps, updated_at = now()
                RETURNING updated_at
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("teamId", teamId)
                .param("steps", json.writeValueAsString(steps))
                .query((rs, row) -> rs.getTimestamp("updated_at").toInstant())
                .single();
        return new EscalationPolicyResponse(teamId, steps, updatedAt);
    }

    public EscalationPolicyResponse get(UUID tenantId, UUID teamId) {
        return find(tenantId, teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team " + teamId + " has no escalation policy"));
    }

    /** @return the policy's levels, or an empty list if the team has no policy */
    List<EscalationStep> steps(UUID tenantId, UUID teamId) {
        return find(tenantId, teamId).map(EscalationPolicyResponse::steps).orElse(List.of());
    }

    private Optional<EscalationPolicyResponse> find(UUID tenantId, UUID teamId) {
        return jdbc.sql("SELECT steps, updated_at FROM escalation_policy WHERE tenant_id = :tenantId AND team_id = :teamId")
                .param("tenantId", tenantId)
                .param("teamId", teamId)
                .query((rs, row) -> new EscalationPolicyResponse(
                        teamId,
                        List.of(json.readValue(rs.getString("steps"), EscalationStep[].class)),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }
}
