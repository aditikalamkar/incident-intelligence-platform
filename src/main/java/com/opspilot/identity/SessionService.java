package com.opspilot.identity;

import com.opspilot.common.Hashing;
import com.opspilot.identity.InvalidSessionException.Reason;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sign-in sessions and their refresh tokens. Each refresh uses the session's current token once and
 * replaces it. A used token presented again was copied by someone, so the whole session ends, unless it
 * comes within the grace period: then it is the same browser sending two refreshes at once.
 */
@Service
class SessionService {

    /** A refresh token for the cookie. */
    record IssuedToken(String value, Instant expiresAt) {
    }

    /** @param rotated the token replacing the one presented; null when a concurrent request already rotated it */
    record Refreshed(UserPrincipal user, IssuedToken rotated) {
    }

    private record TokenRow(UUID sessionId, UUID tenantId, UUID userId, Instant expiresAt, Instant usedAt,
            Instant sessionExpiresAt, Instant revokedAt) {
    }

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    /** Ended sessions are kept a day, so a late request still gets a clear "session ended". */
    private static final Duration RETENTION = Duration.ofDays(1);

    private final JdbcClient jdbc;
    private final UserService users;
    private final SessionProperties properties;
    private final MeterRegistry registry;

    SessionService(JdbcClient jdbc, UserService users, SessionProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.users = users;
        this.properties = properties;
        this.registry = registry;
    }

    @Transactional
    IssuedToken start(UserPrincipal user) {
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant end = now.plus(properties.maxLifetime());
        jdbc.sql("""
                INSERT INTO user_session (id, tenant_id, user_id, created_at, expires_at)
                VALUES (:id, :tenantId, :userId, :now, :end)
                """)
                .param("id", sessionId)
                .param("tenantId", user.tenantId())
                .param("userId", user.userId())
                .param("now", Timestamp.from(now))
                .param("end", Timestamp.from(end))
                .update();
        return issue(sessionId, now, end);
    }

    /**
     * Exchanges a refresh token for the user it belongs to and a replacement token. The user's current
     * role is read from the database, so a role change takes effect at the next refresh.
     */
    @Transactional(noRollbackFor = InvalidSessionException.class)
    Refreshed refresh(String token) {
        if (token == null || token.isBlank()) {
            throw invalid(Reason.MISSING);
        }
        // FOR UPDATE: concurrent refreshes with the same token take turns, so only one can rotate it
        TokenRow row = jdbc.sql("""
                SELECT t.session_id, t.expires_at, t.used_at,
                       s.tenant_id, s.user_id, s.expires_at AS session_expires_at, s.revoked_at
                FROM refresh_token t JOIN user_session s ON s.id = t.session_id
                WHERE t.token_hash = :hash
                FOR UPDATE
                """)
                .param("hash", Hashing.sha256Hex(token))
                .query((rs, n) -> new TokenRow(
                        rs.getObject("session_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),
                        instant(rs.getTimestamp("used_at")),
                        rs.getTimestamp("session_expires_at").toInstant(),
                        instant(rs.getTimestamp("revoked_at"))))
                .optional()
                .orElseThrow(() -> invalid(Reason.UNKNOWN));

        Instant now = Instant.now();
        if (row.revokedAt() != null) {
            throw invalid(Reason.REVOKED);
        }
        if (!row.expiresAt().isAfter(now) || !row.sessionExpiresAt().isAfter(now)) {
            throw invalid(Reason.EXPIRED);
        }
        UserPrincipal user = users.principal(row.tenantId(), row.userId()).orElseThrow(() -> invalid(Reason.UNKNOWN));
        if (row.usedAt() != null) {
            if (row.usedAt().isAfter(now.minus(properties.reuseGracePeriod()))) {
                // Two refreshes raced (a retry, or a browser without cross-tab locking): the first one's
                // response sets the new cookie, so this one gets an access token and leaves the cookie alone
                count("CONCURRENT");
                return new Refreshed(user, null);
            }
            revoke(row.sessionId(), "REUSED");
            log.warn("Refresh token reused for user {}: session {} revoked", row.userId(), row.sessionId());
            throw invalid(Reason.REUSED);
        }
        jdbc.sql("UPDATE refresh_token SET used_at = :now WHERE token_hash = :hash")
                .param("now", Timestamp.from(now))
                .param("hash", Hashing.sha256Hex(token))
                .update();
        count("ROTATED");
        return new Refreshed(user, issue(row.sessionId(), now, row.sessionExpiresAt()));
    }

    /** Ends the session the token belongs to, whichever of its tokens it is. Unknown tokens are ignored. */
    @Transactional
    void end(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        jdbc.sql("""
                UPDATE user_session SET revoked_at = now(), revoked_reason = 'SIGNED_OUT'
                WHERE revoked_at IS NULL
                  AND id = (SELECT session_id FROM refresh_token WHERE token_hash = :hash)
                """)
                .param("hash", Hashing.sha256Hex(token))
                .update();
    }

    /**
     * Deletes tokens and sessions that ended more than a day ago. Used tokens of live sessions stay:
     * they are how a stolen copy is recognised.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    void deleteEnded() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(RETENTION));
        jdbc.sql("DELETE FROM refresh_token WHERE expires_at < :cutoff").param("cutoff", cutoff).update();
        jdbc.sql("""
                DELETE FROM user_session s
                WHERE s.expires_at < :cutoff OR s.revoked_at < :cutoff
                   OR NOT EXISTS (SELECT 1 FROM refresh_token t WHERE t.session_id = s.id)
                """)
                .param("cutoff", cutoff)
                .update();
    }

    /** Never longer than the session itself. */
    private IssuedToken issue(UUID sessionId, Instant now, Instant sessionEnd) {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(properties.idleTimeout());
        if (expiresAt.isAfter(sessionEnd)) {
            expiresAt = sessionEnd;
        }
        jdbc.sql("""
                INSERT INTO refresh_token (id, session_id, token_hash, created_at, expires_at)
                VALUES (:id, :sessionId, :hash, :now, :expiresAt)
                """)
                .param("id", UUID.randomUUID())
                .param("sessionId", sessionId)
                .param("hash", Hashing.sha256Hex(value))
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
        return new IssuedToken(value, expiresAt);
    }

    private void revoke(UUID sessionId, String reason) {
        jdbc.sql("UPDATE user_session SET revoked_at = now(), revoked_reason = :reason WHERE id = :id")
                .param("reason", reason)
                .param("id", sessionId)
                .update();
    }

    private InvalidSessionException invalid(Reason reason) {
        count(reason.name());
        return new InvalidSessionException(reason);
    }

    /** A rise in REUSED means refresh tokens are being stolen. */
    private void count(String outcome) {
        registry.counter("opspilot.auth.refreshes", "outcome", outcome).increment();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
