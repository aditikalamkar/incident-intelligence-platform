package com.opspilot.identity;

import com.opspilot.common.Hashing;
import com.opspilot.common.ResourceNotFoundException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    public ApiKeyService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Only the SHA-256 hash of a key is stored, so a database leak does not expose usable keys. */
    public Optional<ApiKeyPrincipal> authenticate(String rawKey) {
        return jdbc.sql("SELECT id, tenant_id FROM api_key WHERE key_hash = :hash AND NOT revoked")
                .param("hash", Hashing.sha256Hex(rawKey))
                .query((rs, row) -> new ApiKeyPrincipal(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class)))
                .optional();
    }

    public CreatedApiKeyResponse create(UUID tenantId, String label) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String rawKey = "ops_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        UUID id = UUID.randomUUID();
        Instant createdAt = jdbc.sql("""
                INSERT INTO api_key (id, tenant_id, key_hash, label) VALUES (:id, :tenantId, :hash, :label)
                RETURNING created_at
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("hash", Hashing.sha256Hex(rawKey))
                .param("label", label)
                .query((rs, row) -> rs.getTimestamp("created_at").toInstant())
                .single();
        return new CreatedApiKeyResponse(id, label, rawKey, createdAt);
    }

    public List<ApiKeyResponse> list(UUID tenantId) {
        return jdbc.sql("SELECT id, label, revoked, created_at FROM api_key WHERE tenant_id = :tenantId ORDER BY created_at")
                .param("tenantId", tenantId)
                .query((rs, row) -> new ApiKeyResponse(
                        rs.getObject("id", UUID.class),
                        rs.getString("label"),
                        rs.getBoolean("revoked"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    public void revoke(UUID tenantId, UUID apiKeyId) {
        int updated = jdbc.sql("UPDATE api_key SET revoked = TRUE WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", apiKeyId)
                .update();
        if (updated == 0) {
            throw new ResourceNotFoundException("API key " + apiKeyId + " not found");
        }
    }
}
