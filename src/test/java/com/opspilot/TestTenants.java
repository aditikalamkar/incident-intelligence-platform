package com.opspilot;

import com.opspilot.common.Hashing;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Creates an isolated tenant per test so tests never see each other's data. */
public final class TestTenants {

    public record TestTenant(UUID tenantId, String apiKey) {
    }

    private TestTenants() {
    }

    public static TestTenant create(JdbcClient jdbc) {
        UUID tenantId = UUID.randomUUID();
        String apiKey = "test-" + UUID.randomUUID();
        jdbc.sql("INSERT INTO tenant (id, name) VALUES (:id, :name)")
                .param("id", tenantId)
                .param("name", "tenant-" + tenantId)
                .update();
        jdbc.sql("INSERT INTO api_key (id, tenant_id, key_hash, label) VALUES (:id, :tenantId, :hash, 'test')")
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("hash", Hashing.sha256Hex(apiKey))
                .update();
        return new TestTenant(tenantId, apiKey);
    }
}
