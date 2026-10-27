package com.opspilot.identity;

import com.opspilot.common.Hashing;
import com.opspilot.tenant.TenantPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyService {

    private final JdbcClient jdbc;

    public ApiKeyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Only the SHA-256 hash of a key is stored, so a database leak does not expose usable keys. */
    public Optional<TenantPrincipal> authenticate(String rawKey) {
        return jdbc.sql("SELECT id, tenant_id FROM api_key WHERE key_hash = :hash AND NOT revoked")
                .param("hash", Hashing.sha256Hex(rawKey))
                .query((rs, row) -> new TenantPrincipal(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class)))
                .optional();
    }
}
