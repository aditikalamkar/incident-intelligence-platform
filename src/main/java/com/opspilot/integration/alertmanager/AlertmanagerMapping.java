package com.opspilot.integration.alertmanager;

import com.opspilot.alerting.GenericAlertRequest;
import com.opspilot.incident.Severity;
import com.opspilot.integration.alertmanager.AlertmanagerWebhook.Alert;
import java.util.Locale;
import java.util.Map;

/** Translates Alertmanager's conventions into OpsPilot's alert model. */
final class AlertmanagerMapping {

    static final String SOURCE = "alertmanager";

    private static final int MAX_SERVICE = 100;
    private static final int MAX_ALERT_NAME = 150;
    private static final int MAX_SUMMARY = 2000;

    private AlertmanagerMapping() {
    }

    /**
     * Alertmanager re-sends a firing alert on every repeat interval with the same fingerprint and
     * start time, so together they identify one firing episode. If the alert clears and fires
     * again later, the new start time makes it a new event.
     */
    static String eventId(Alert alert) {
        return alert.fingerprint() + "@" + alert.startsAt();
    }

    /**
     * Grouping uses OpsPilot's default (source, service, alert name) rather than Alertmanager's
     * fingerprint, which includes labels like {@code instance}: three pods with the same alert become
     * one incident with three alerts, not three incidents.
     */
    static GenericAlertRequest toRequest(Alert alert) {
        Map<String, String> labels = alert.labels();
        Map<String, String> annotations = alert.annotations() != null ? alert.annotations() : Map.of();
        String summary = firstPresent(annotations, "summary", "description");
        return new GenericAlertRequest(
                eventId(alert),
                truncate(service(labels), MAX_SERVICE),
                truncate(labels.get("alertname"), MAX_ALERT_NAME),
                severity(labels.get("severity")),
                summary == null ? null : truncate(summary, MAX_SUMMARY),
                null,
                labels);
    }

    /** The {@code service} label if present, otherwise the Prometheus {@code job}. */
    static String service(Map<String, String> labels) {
        String service = firstPresent(labels, "service", "job");
        return service != null ? service : "unknown";
    }

    /** Maps the common {@code severity} label conventions; anything unrecognised is MEDIUM. */
    static Severity severity(String label) {
        if (label == null) {
            return Severity.MEDIUM;
        }
        return switch (label.trim().toLowerCase(Locale.ROOT)) {
            case "critical", "page", "p1" -> Severity.CRITICAL;
            case "high", "error", "major", "p2" -> Severity.HIGH;
            case "low", "info", "minor", "none", "p4", "p5" -> Severity.LOW;
            default -> Severity.MEDIUM;
        };
    }

    private static String firstPresent(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
