package com.opspilot.identity;

import com.opspilot.common.ConflictException;
import com.opspilot.common.ResourceNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TeamService {

    private final JdbcClient jdbc;
    private final UserService users;

    public TeamService(JdbcClient jdbc, UserService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public TeamResponse create(UUID tenantId, String name) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO team (id, tenant_id, name) VALUES (:id, :tenantId, :name)")
                    .param("id", id)
                    .param("tenantId", tenantId)
                    .param("name", name)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("A team named " + name + " already exists");
        }
        return new TeamResponse(id, name, List.of());
    }

    public List<TeamResponse> list(UUID tenantId) {
        Map<UUID, List<UUID>> members = new HashMap<>();
        jdbc.sql("""
                SELECT tm.team_id, tm.user_id FROM team_member tm
                JOIN team t ON t.id = tm.team_id
                WHERE t.tenant_id = :tenantId
                """)
                .param("tenantId", tenantId)
                .query(rs -> {
                    members.computeIfAbsent(rs.getObject("team_id", UUID.class), id -> new ArrayList<>())
                            .add(rs.getObject("user_id", UUID.class));
                });
        return jdbc.sql("SELECT id, name FROM team WHERE tenant_id = :tenantId ORDER BY name")
                .param("tenantId", tenantId)
                .query((rs, row) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new TeamResponse(id, rs.getString("name"), members.getOrDefault(id, List.of()));
                })
                .list();
    }

    /** Idempotent: adding an existing member succeeds without change. */
    public void addMember(UUID tenantId, UUID teamId, UUID userId) {
        requireTeam(tenantId, teamId);
        if (!users.existsInTenant(tenantId, userId)) {
            throw new ResourceNotFoundException("User " + userId + " not found");
        }
        jdbc.sql("INSERT INTO team_member (team_id, user_id) VALUES (:teamId, :userId) ON CONFLICT DO NOTHING")
                .param("teamId", teamId)
                .param("userId", userId)
                .update();
    }

    public void removeMember(UUID tenantId, UUID teamId, UUID userId) {
        requireTeam(tenantId, teamId);
        jdbc.sql("DELETE FROM team_member WHERE team_id = :teamId AND user_id = :userId")
                .param("teamId", teamId)
                .param("userId", userId)
                .update();
    }

    private void requireTeam(UUID tenantId, UUID teamId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM team WHERE tenant_id = :tenantId AND id = :id)")
                .param("tenantId", tenantId)
                .param("id", teamId)
                .query(Boolean.class)
                .single();
        if (!exists) {
            throw new ResourceNotFoundException("Team " + teamId + " not found");
        }
    }
}
