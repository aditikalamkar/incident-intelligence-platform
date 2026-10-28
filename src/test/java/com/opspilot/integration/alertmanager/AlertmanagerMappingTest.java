package com.opspilot.integration.alertmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.opspilot.alerting.GenericAlertRequest;
import com.opspilot.incident.Severity;
import com.opspilot.integration.alertmanager.AlertmanagerWebhook.Alert;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AlertmanagerMappingTest {

    private static final Instant STARTS_AT = Instant.parse("2026-10-28T09:42:00Z");

    @ParameterizedTest
    @CsvSource({
            "critical, CRITICAL", "CRITICAL, CRITICAL", "page, CRITICAL", "p1, CRITICAL",
            "high, HIGH", "error, HIGH", "major, HIGH",
            "warning, MEDIUM", "medium, MEDIUM",
            "info, LOW", "low, LOW", "none, LOW",
            "something-custom, MEDIUM"})
    void mapsCommonSeverityLabels(String label, Severity expected) {
        assertThat(AlertmanagerMapping.severity(label)).isEqualTo(expected);
    }

    @Test
    void missingSeverityIsMedium() {
        assertThat(AlertmanagerMapping.severity(null)).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void serviceComesFromServiceLabelThenJob() {
        assertThat(AlertmanagerMapping.service(Map.of("service", "payment-api", "job", "node"))).isEqualTo("payment-api");
        assertThat(AlertmanagerMapping.service(Map.of("job", "payment-api"))).isEqualTo("payment-api");
        assertThat(AlertmanagerMapping.service(Map.of("alertname", "HighLatency"))).isEqualTo("unknown");
    }

    @Test
    void eventIdIdentifiesOneFiringEpisode() {
        Alert alert = alert(Map.of("alertname", "HighLatency"), Map.of());

        assertThat(AlertmanagerMapping.eventId(alert)).isEqualTo("abc123@2026-10-28T09:42:00Z");
    }

    @Test
    void requestUsesDefaultGroupingAndFallsBackToDescription() {
        Alert alert = alert(
                Map.of("alertname", "HighLatency", "service", "payment-api", "severity", "critical", "instance", "pod-a"),
                Map.of("description", "p95 above 2s"));

        GenericAlertRequest request = AlertmanagerMapping.toRequest(alert);

        assertThat(request.service()).isEqualTo("payment-api");
        assertThat(request.alertName()).isEqualTo("HighLatency");
        assertThat(request.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(request.summary()).isEqualTo("p95 above 2s");
        assertThat(request.fingerprint()).as("instance-specific fingerprint must not split the incident").isNull();
        assertThat(request.labels()).containsEntry("instance", "pod-a");
    }

    @Test
    void longValuesAreTruncatedToColumnLimits() {
        Alert alert = alert(Map.of("alertname", "A".repeat(500), "service", "s".repeat(500)), null);

        GenericAlertRequest request = AlertmanagerMapping.toRequest(alert);

        assertThat(request.alertName()).hasSize(150);
        assertThat(request.service()).hasSize(100);
        assertThat(request.summary()).isNull();
    }

    private static Alert alert(Map<String, String> labels, Map<String, String> annotations) {
        return new Alert("firing", labels, annotations, STARTS_AT, "http://prometheus:9090/graph", "abc123");
    }
}
