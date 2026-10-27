package com.opspilot.notification;

import com.opspilot.identity.TeamService;
import com.opspilot.identity.UserService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records notifications to send; {@link NotificationDispatcher} delivers them. Writing them in the
 * caller's transaction means a page is never lost (it commits with the escalation that caused it)
 * and never sent for an escalation that rolled back.
 */
@Service
public class NotificationService {

    private final JdbcClient jdbc;
    private final TeamService teams;
    private final UserService users;

    public NotificationService(JdbcClient jdbc, TeamService teams, UserService users) {
        this.jdbc = jdbc;
        this.teams = teams;
        this.users = users;
    }

    /** Idempotent per (incident, level, user): running the same escalation level again pages nobody twice. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void page(UUID tenantId, UUID incidentId, UUID teamId, List<UUID> userIds, String subject, int level) {
        Optional<String> slackWebhook = teams.slackWebhook(tenantId, teamId);
        Channel channel = slackWebhook.isPresent() ? Channel.SLACK : Channel.LOG;
        for (UUID userId : userIds) {
            String name = users.get(tenantId, userId).name();
            jdbc.sql("""
                    INSERT INTO notification (id, tenant_id, incident_id, user_id, channel, target, message, dedupe_key)
                    VALUES (:id, :tenantId, :incidentId, :userId, :channel, :target, :message, :dedupeKey)
                    ON CONFLICT (dedupe_key) DO NOTHING
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", tenantId)
                    .param("incidentId", incidentId)
                    .param("userId", userId)
                    .param("channel", channel.name())
                    .param("target", slackWebhook.orElse(null))
                    .param("message", "%s is unacknowledged: paging %s (escalation level %d)"
                            .formatted(subject, name, level))
                    .param("dedupeKey", "escalation:%s:%d:%s".formatted(incidentId, level, userId))
                    .update();
        }
    }
}
