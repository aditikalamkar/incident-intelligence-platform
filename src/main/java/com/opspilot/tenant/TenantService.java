package com.opspilot.tenant;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TenantService {

    private final JdbcClient jdbc;

    public TenantService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID create(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenant (id, name) VALUES (:id, :name)")
                .param("id", id)
                .param("name", name)
                .update();
        return id;
    }
}
