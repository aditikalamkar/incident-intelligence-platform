package com.opspilot.ai;

import com.opspilot.ai.InvestigationResult.Item;
import com.opspilot.ai.InvestigationResult.Source;
import com.opspilot.ai.InvestigationResult.Source.Kind;
import com.opspilot.alerting.IncidentAlerts.AlertRecord;
import com.opspilot.incident.IncidentEventType;
import com.opspilot.incident.IncidentResponse;
import com.opspilot.incident.TimelineEventResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the assistant is told about an incident. Every alert, timeline event and earlier incident gets a
 * short reference (A1, E3, P2) for the model to cite; {@link #resolve} turns the citations back into links
 * and drops any that were not in the briefing. Free text from people and monitoring systems is quoted as
 * JSON strings, so it reads as data rather than as part of the briefing.
 *
 * @param asOf when the incident's data was read
 */
public record Briefing(String text, Map<String, Source> references, Instant asOf) {

    /** How far back earlier incidents on the same service are included. */
    static final Duration HISTORY = Duration.ofDays(30);
    /** Only the latest events are sent: a long incident would otherwise make every investigation slow and costly. */
    static final int MAX_EVENTS = 150;
    static final int MAX_COMMENT_LENGTH = 2_000;
    static final int MAX_PAST_COMMENT_LENGTH = 500;
    static final int MAX_PAYLOAD_LENGTH = 3_000;
    /** Findings and next steps kept, each. */
    static final int MAX_ITEMS = 5;

    /** Everything known about the incident when the investigation starts. */
    record Facts(IncidentResponse incident, String serviceName, Map<UUID, String> userNames,
            List<AlertRecord> alerts, List<TimelineEventResponse> timeline, List<PastIncident> pastIncidents,
            Instant now) {
    }

    /** An earlier incident on the same service, with its last comments: often where the fix was written down. */
    record PastIncident(IncidentResponse incident, List<TimelineEventResponse> comments) {
    }

    static Briefing of(Facts facts, JsonMapper json) {
        return new Writer(facts, json).write();
    }

    InvestigationResult resolve(Analysis analysis) {
        if (analysis.summary() == null || analysis.summary().isBlank()) {
            throw new InvestigationFailedException("Claude returned an empty analysis. Try again.");
        }
        return new InvestigationResult(analysis.summary().strip(), items(analysis.findings()),
                items(analysis.nextSteps()), asOf);
    }

    private List<Item> items(List<Analysis.Point> points) {
        if (points == null) {
            return List.of();
        }
        return points.stream()
                .filter(point -> point != null && point.text() != null && !point.text().isBlank())
                .limit(MAX_ITEMS)
                .map(point -> new Item(point.text().strip(), sources(point.sources())))
                .toList();
    }

    /** Accepts "E3", "[E3]" or "e3"; references that were not in the briefing are dropped. */
    private List<Source> sources(List<String> cited) {
        if (cited == null) {
            return List.of();
        }
        return cited.stream()
                .filter(Objects::nonNull)
                .map(reference -> references.get(reference.replaceAll("[\\[\\]\\s]", "").toUpperCase(Locale.ROOT)))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static final class Writer {

        private final Facts facts;
        private final JsonMapper json;
        private final StringBuilder out = new StringBuilder();
        private final Map<String, Source> references = new LinkedHashMap<>();
        private final Map<UUID, String> alertReferences = new HashMap<>();

        Writer(Facts facts, JsonMapper json) {
            this.facts = facts;
            this.json = json;
        }

        Briefing write() {
            incident();
            alerts();
            timeline();
            pastIncidents();
            return new Briefing(out.toString(), Collections.unmodifiableMap(references), facts.now());
        }

        private void incident() {
            IncidentResponse incident = facts.incident();
            out.append("Investigate this incident. The time now is ").append(time(facts.now())).append(".\n\n");
            out.append("<incident>\n");
            field("Title", quote(incident.title()));
            field("Service", facts.serviceName() == null ? "unknown" : quote(facts.serviceName()));
            field("Severity", incident.severity());
            field("Status", incident.status());
            field("Assigned to", name(incident.assigneeId()));
            field("Opened", time(incident.openedAt()));
            if (incident.acknowledgedAt() != null) {
                field("Acknowledged", time(incident.acknowledgedAt()));
            }
            if (incident.resolvedAt() != null) {
                field("Resolved", time(incident.resolvedAt()));
            }
            field("Alerts grouped into it", incident.alertCount());
            out.append("</incident>\n\n");
        }

        private void alerts() {
            List<AlertRecord> alerts = facts.alerts();
            out.append("<alerts>\n");
            if (alerts.isEmpty()) {
                out.append("No alert details are stored.\n");
            } else {
                out.append(alerts.size() < facts.incident().alertCount() ? "The latest " + alerts.size() : "All")
                        .append(" alerts, oldest first, as the monitoring system sent them.\n");
            }
            for (AlertRecord alert : alerts) {
                String reference = "A" + (alertReferences.size() + 1);
                alertReferences.put(alert.id(), reference);
                references.put(reference, new Source(Kind.ALERT, alert.id(), alertName(alert), alert.receivedAt()));
                out.append('[').append(reference).append("] from ").append(quote(alert.source()))
                        .append(", received ").append(time(alert.receivedAt()))
                        .append(alert.resolvedAt() == null ? ", still firing" : ", cleared " + time(alert.resolvedAt()))
                        .append(":\n")
                        .append(truncate(json.writeValueAsString(alert.payload()), MAX_PAYLOAD_LENGTH))
                        .append('\n');
            }
            out.append("</alerts>\n\n");
        }

        private void timeline() {
            List<TimelineEventResponse> events = facts.timeline().stream()
                    .filter(event -> !isInvestigation(event.type()))
                    .toList();
            int skipped = Math.max(0, events.size() - MAX_EVENTS);
            out.append("<timeline>\nOldest first.");
            if (skipped > 0) {
                out.append(" The first ").append(skipped).append(" events are left out.");
            }
            out.append('\n');
            for (int i = skipped; i < events.size(); i++) {
                TimelineEventResponse event = events.get(i);
                String reference = "E" + (i + 1);
                references.put(reference, new Source(Kind.EVENT, event.id(), describe(event), event.createdAt()));
                out.append('[').append(reference).append("] ").append(time(event.createdAt())).append(' ')
                        .append(describe(event)).append(alertReference(event));
                String body = commentBody(event);
                if (body != null) {
                    out.append(": ").append(quote(truncate(body, MAX_COMMENT_LENGTH)));
                }
                out.append('\n');
            }
            out.append("</timeline>\n\n");
        }

        private void pastIncidents() {
            List<PastIncident> past = facts.pastIncidents();
            out.append("<past_incidents>\n");
            out.append(past.isEmpty() ? "None" : "Earlier incidents")
                    .append(" on this service in the last ").append(HISTORY.toDays()).append(" days")
                    .append(past.isEmpty() ? ".\n" : ", newest first.\n");
            for (int i = 0; i < past.size(); i++) {
                IncidentResponse incident = past.get(i).incident();
                String reference = "P" + (i + 1);
                references.put(reference, new Source(Kind.INCIDENT, incident.id(), incident.title(), incident.openedAt()));
                out.append('[').append(reference).append("] ").append(quote(incident.title()))
                        .append(", ").append(incident.severity())
                        .append(", opened ").append(time(incident.openedAt()))
                        .append(incident.resolvedAt() == null ? ", not resolved" : ", resolved " + time(incident.resolvedAt()))
                        .append('\n');
                for (TimelineEventResponse comment : past.get(i).comments()) {
                    String body = commentBody(comment);
                    if (body != null) {
                        out.append("  Comment by ").append(name(comment.actorId()))
                                .append(" at ").append(time(comment.createdAt())).append(": ")
                                .append(quote(truncate(body, MAX_PAST_COMMENT_LENGTH))).append('\n');
                    }
                }
            }
            out.append("</past_incidents>\n");
        }

        /** In the user's words: also the label of the event's link in the result. */
        private String describe(TimelineEventResponse event) {
            JsonNode details = details(event);
            String actor = name(event.actorId());
            return switch (event.type()) {
                case INCIDENT_CREATED -> "Incident opened by an alert";
                case ALERT_GROUPED -> "Another alert grouped into the incident";
                case ALERT_RESOLVED -> "An alert cleared";
                case ACKNOWLEDGED -> "Acknowledged by " + actor;
                case ASSIGNED -> assigned(event, uuid(details.path("assigneeId")));
                case COMMENT -> "Comment by " + actor;
                case ESCALATED -> "Escalated to level " + details.path("level").asInt() + paged(details);
                case RESOLVED -> event.actorId() == null
                        ? "Resolved automatically: all alerts cleared"
                        : "Resolved by " + actor;
                case INVESTIGATION_COMPLETED, INVESTIGATION_FAILED -> "Assistant investigation for " + actor;
            };
        }

        private String assigned(TimelineEventResponse event, UUID assigneeId) {
            if (event.actorId() == null) {
                return "Assigned to " + name(assigneeId);
            }
            return event.actorId().equals(assigneeId)
                    ? name(assigneeId) + " took the incident"
                    : "Assigned to " + name(assigneeId) + " by " + name(event.actorId());
        }

        private String paged(JsonNode details) {
            List<String> names = details.path("notifiedUserIds").values().stream()
                    .map(id -> name(uuid(id)))
                    .toList();
            return names.isEmpty() ? "" : ", paging " + String.join(", ", names);
        }

        private String alertReference(TimelineEventResponse event) {
            String reference = alertReferences.get(uuid(details(event).path("alertId")));
            return reference == null ? "" : " (" + reference + ")";
        }

        private String alertName(AlertRecord alert) {
            JsonNode payload = alert.payload();
            String name = payload.path("alertName").stringValue(null); // generic webhook
            if (name == null) {
                name = payload.path("labels").path("alertname").stringValue(null); // Alertmanager
            }
            return name != null ? name : "Alert";
        }

        private String name(UUID userId) {
            return userId == null ? "nobody" : facts.userNames().getOrDefault(userId, "an unknown user");
        }

        private JsonNode details(TimelineEventResponse event) {
            return event.details() != null ? event.details() : json.missingNode();
        }

        private void field(String name, Object value) {
            out.append(name).append(": ").append(value).append('\n');
        }

        private String quote(String text) {
            return json.writeValueAsString(text);
        }

        /** To the second: finer precision costs tokens and tells the model nothing. */
        private static String time(Instant instant) {
            return instant.truncatedTo(ChronoUnit.SECONDS).toString();
        }

        private static String commentBody(TimelineEventResponse event) {
            return event.type() == IncidentEventType.COMMENT && event.details() != null
                    ? event.details().path("body").stringValue(null)
                    : null;
        }

        private static boolean isInvestigation(IncidentEventType type) {
            return type == IncidentEventType.INVESTIGATION_COMPLETED || type == IncidentEventType.INVESTIGATION_FAILED;
        }

        private static UUID uuid(JsonNode node) {
            String value = node.stringValue(null);
            try {
                return value == null ? null : UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static String truncate(String text, int max) {
            if (text.length() <= max) {
                return text;
            }
            int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
            return text.substring(0, end) + "… (truncated)";
        }
    }
}
