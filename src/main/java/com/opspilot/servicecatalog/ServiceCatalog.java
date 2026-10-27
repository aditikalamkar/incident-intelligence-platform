package com.opspilot.servicecatalog;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ServiceCatalog {

    private final JdbcClient jdbc;

    public ServiceCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
}
