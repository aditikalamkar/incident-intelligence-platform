package com.opspilot.servicecatalog;

import com.opspilot.common.ResourceNotFoundException;
import com.opspilot.identity.TeamService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ServiceCatalog {

    private static final RowMapper<ServiceResponse> SERVICE = (rs, row) -> new ServiceResponse(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getObject("team_id", UUID.class));

    private final JdbcClient jdbc;
    private final TeamService teams;

    public ServiceCatalog(JdbcClient jdbc, TeamService teams) {
        this.jdbc = jdbc;
        this.teams = teams;
    }

    /** Services are registered automatically the first time an alert names them. */
    public UUID resolveOrRegister(UUID tenantId, String name) {
        jdbc.sql("""
                INSERT INTO service (id, tenant_id, name) VALUES (:id, :tenantId, :name)
                ON CONFLICT (tenant_id, name) DO NOTHING
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("name", name)
                .update();
        return jdbc.sql("SELECT id FROM service WHERE tenant_id = :tenantId AND name = :name")
                .param("tenantId", tenantId)
                .param("name", name)
                .query(UUID.class)
                .single();
    }

    public List<ServiceResponse> list(UUID tenantId) {
        return jdbc.sql("SELECT id, name, team_id FROM service WHERE tenant_id = :tenantId ORDER BY name")
                .param("tenantId", tenantId)
                .query(SERVICE)
                .list();
    }

    public ServiceResponse assignTeam(UUID tenantId, UUID serviceId, UUID teamId) {
        if (!teams.exists(tenantId, teamId)) {
            throw new ResourceNotFoundException("Team " + teamId + " not found");
        }
        return jdbc.sql("""
                UPDATE service SET team_id = :teamId WHERE tenant_id = :tenantId AND id = :id
                RETURNING id, name, team_id
                """)
                .param("teamId", teamId)
                .param("tenantId", tenantId)
                .param("id", serviceId)
                .query(SERVICE)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Service " + serviceId + " not found"));
    }

    public Optional<UUID> owningTeam(UUID tenantId, UUID serviceId) {
        return jdbc.sql("SELECT team_id FROM service WHERE tenant_id = :tenantId AND id = :id AND team_id IS NOT NULL")
                .param("tenantId", tenantId)
                .param("id", serviceId)
                .query(UUID.class)
                .optional();
    }
}
