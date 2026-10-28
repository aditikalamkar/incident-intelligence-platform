package com.opspilot.incident;

import com.opspilot.common.AfterCommit;
import com.opspilot.common.DatabaseGauges;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Platform-wide incident metrics. Never tagged by tenant, to keep the number of time series bounded. */
@Component
class IncidentMetrics {

    private final MeterRegistry registry;

    IncidentMetrics(MeterRegistry registry, DatabaseGauges gauges) {
        this.registry = registry;
        for (IncidentStatus status : List.of(IncidentStatus.TRIGGERED, IncidentStatus.ACKNOWLEDGED)) {
            gauges.register("opspilot.incidents.open", "Incidents not yet resolved",
                    "SELECT count(*) FROM incident WHERE status = '" + status.name() + "'", "status", status.name());
        }
    }

    void opened(Severity severity) {
        AfterCommit.run(() -> registry.counter("opspilot.incidents.opened", "severity", severity.name()).increment());
    }

    void acknowledged(Incident incident) {
        record("opspilot.incidents.time.to.acknowledge", incident.getOpenedAt(), incident.getAcknowledgedAt(),
                "severity", incident.getSeverity().name());
    }

    void resolved(Incident incident, boolean automatically) {
        record("opspilot.incidents.time.to.resolve", incident.getOpenedAt(), incident.getResolvedAt(),
                "severity", incident.getSeverity().name(), "resolution", automatically ? "AUTOMATIC" : "MANUAL");
    }

    private void record(String name, Instant from, Instant to, String... tags) {
        Duration duration = Duration.between(from, to);
        AfterCommit.run(() -> Timer.builder(name)
                .tags(tags)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofSeconds(1))
                .maximumExpectedValue(Duration.ofDays(1))
                .register(registry)
                .record(duration));
    }
}
