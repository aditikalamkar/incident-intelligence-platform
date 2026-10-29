package com.opspilot.incident;

import com.opspilot.common.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Builds one SQL query from whichever filters are present. Results are ordered newest first and
 * paged with a keyset cursor (the last row's opened_at and id) rather than OFFSET: a page never
 * repeats or skips incidents when new ones open between requests, and deep pages cost the same as
 * the first.
 */
@Component
class IncidentSearch {

    private static final int MAX_TEXT_LENGTH = 200;
    private static final int MAX_TERMS = 5;

    private static final RowMapper<IncidentResponse> INCIDENT = (rs, row) -> new IncidentResponse(
            rs.getObject("id", UUID.class),
            rs.getObject("service_id", UUID.class),
            rs.getString("title"),
            Severity.valueOf(rs.getString("severity")),
            IncidentStatus.valueOf(rs.getString("status")),
            rs.getObject("assignee_id", UUID.class),
            rs.getInt("alert_count"),
            rs.getLong("version"),
            instant(rs, "opened_at"),
            instant(rs, "acknowledged_at"),
            instant(rs, "resolved_at"));

    private record Cursor(Instant openedAt, UUID id) {
    }

    private final JdbcClient jdbc;

    IncidentSearch(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    IncidentPage search(UUID tenantId, IncidentQuery query) {
        if (query.serviceIds() != null && query.serviceIds().isEmpty()) {
            return new IncidentPage(List.of(), null);
        }
        StringBuilder sql = new StringBuilder("""
                SELECT i.id, i.service_id, i.title, i.severity, i.status, i.assignee_id, i.alert_count, i.version,
                       i.opened_at, i.acknowledged_at, i.resolved_at
                FROM incident i
                WHERE i.tenant_id = :tenantId
                """);
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);

        if (query.statuses() != null && !query.statuses().isEmpty()) {
            sql.append(" AND i.status IN (:statuses)");
            params.put("statuses", query.statuses().stream().map(Enum::name).toList());
        }
        if (query.severities() != null && !query.severities().isEmpty()) {
            sql.append(" AND i.severity IN (:severities)");
            params.put("severities", query.severities().stream().map(Enum::name).toList());
        }
        if (query.serviceIds() != null) {
            sql.append(" AND i.service_id IN (:serviceIds)");
            params.put("serviceIds", query.serviceIds());
        }
        if (query.assigneeId() != null) {
            sql.append(" AND i.assignee_id = :assigneeId");
            params.put("assigneeId", query.assigneeId());
        }
        if (query.unassigned()) {
            sql.append(" AND i.assignee_id IS NULL");
        }
        if (query.openedAfter() != null) {
            sql.append(" AND i.opened_at >= :openedAfter");
            params.put("openedAfter", utc(query.openedAfter()));
        }
        if (query.openedBefore() != null) {
            sql.append(" AND i.opened_at < :openedBefore");
            params.put("openedBefore", utc(query.openedBefore()));
        }
        List<String> terms = terms(query.text());
        for (int i = 0; i < terms.size(); i++) {
            String param = "term" + i;
            sql.append("""
                     AND (i.title ILIKE :%1$s
                          OR EXISTS (SELECT 1 FROM incident_event c
                                     WHERE c.incident_id = i.id AND c.type = 'COMMENT'
                                       AND c.details->>'body' ILIKE :%1$s))
                    """.formatted(param));
            params.put(param, "%" + escapeLike(terms.get(i)) + "%");
        }
        if (query.cursor() != null) {
            Cursor cursor = decode(query.cursor());
            sql.append(" AND (i.opened_at, i.id) < (:cursorOpenedAt, :cursorId)");
            params.put("cursorOpenedAt", utc(cursor.openedAt()));
            params.put("cursorId", cursor.id());
        }
        // One extra row tells whether there is a next page without a separate count query.
        sql.append(" ORDER BY i.opened_at DESC, i.id DESC LIMIT :limit");
        params.put("limit", query.limit() + 1);

        List<IncidentResponse> rows = jdbc.sql(sql.toString()).params(params).query(INCIDENT).list();
        if (rows.size() <= query.limit()) {
            return new IncidentPage(rows, null);
        }
        List<IncidentResponse> page = rows.subList(0, query.limit());
        IncidentResponse last = page.getLast();
        return new IncidentPage(List.copyOf(page), encode(new Cursor(last.openedAt(), last.id())));
    }

    private static List<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new InvalidRequestException("Search text is limited to " + MAX_TEXT_LENGTH + " characters");
        }
        List<String> terms = Arrays.stream(text.trim().split("\\s+")).distinct().toList();
        if (terms.size() > MAX_TERMS) {
            throw new InvalidRequestException("Search is limited to " + MAX_TERMS + " words");
        }
        return terms;
    }

    /** Searching for "100%" or "db_pool" must match those characters literally, not as wildcards. */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String encode(Cursor cursor) {
        String raw = cursor.openedAt() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new InvalidRequestException("Invalid cursor");
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
