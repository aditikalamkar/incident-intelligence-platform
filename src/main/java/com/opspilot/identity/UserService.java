package com.opspilot.identity;

import com.opspilot.common.ConflictException;
import com.opspilot.common.ResourceNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    record Credentials(UserPrincipal principal, String passwordHash) {
    }

    private static final RowMapper<UserResponse> USER = (rs, row) -> new UserResponse(
            rs.getObject("id", UUID.class),
            rs.getString("email"),
            rs.getString("name"),
            Role.valueOf(rs.getString("role")),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;

    public UserService(JdbcClient jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    public UserResponse create(UUID tenantId, String email, String name, Role role, String rawPassword) {
        UUID id = UUID.randomUUID();
        String normalizedEmail = normalize(email);
        try {
            jdbc.sql("""
                    INSERT INTO app_user (id, tenant_id, email, name, role, password_hash)
                    VALUES (:id, :tenantId, :email, :name, :role, :passwordHash)
                    """)
                    .param("id", id)
                    .param("tenantId", tenantId)
                    .param("email", normalizedEmail)
                    .param("name", name)
                    .param("role", role.name())
                    .param("passwordHash", passwordEncoder.encode(rawPassword))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ConflictException("A user with email " + normalizedEmail + " already exists");
        }
        return get(tenantId, id);
    }

    public UserResponse get(UUID tenantId, UUID userId) {
        return jdbc.sql("SELECT id, email, name, role, created_at FROM app_user WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", userId)
                .query(USER)
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("User " + userId + " not found"));
    }

    public List<UserResponse> list(UUID tenantId) {
        return jdbc.sql("SELECT id, email, name, role, created_at FROM app_user WHERE tenant_id = :tenantId ORDER BY name")
                .param("tenantId", tenantId)
                .query(USER)
                .list();
    }

    public boolean existsInTenant(UUID tenantId, UUID userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE tenant_id = :tenantId AND id = :id)")
                .param("tenantId", tenantId)
                .param("id", userId)
                .query(Boolean.class)
                .single();
    }

    Optional<Credentials> findCredentials(String email) {
        return jdbc.sql("SELECT id, tenant_id, role, password_hash FROM app_user WHERE lower(email) = :email")
                .param("email", normalize(email))
                .query((rs, row) -> new Credentials(
                        new UserPrincipal(
                                rs.getObject("id", UUID.class),
                                rs.getObject("tenant_id", UUID.class),
                                Role.valueOf(rs.getString("role"))),
                        rs.getString("password_hash")))
                .optional();
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
