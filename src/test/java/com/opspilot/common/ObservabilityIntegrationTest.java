package com.opspilot.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.TestFixtures.TestTenant;
import com.opspilot.alerting.AlertIngestionService;
import com.opspilot.alerting.GenericAlertRequest;
import com.opspilot.identity.ApiKeyAuthenticationFilter;
import com.opspilot.incident.IncidentService;
import com.opspilot.incident.Severity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class ObservabilityIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry registry;

    @Autowired
    AlertIngestionService ingestion;

    @Autowired
    IncidentService incidents;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TestFixtures fixtures;

    TestTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = fixtures.tenant();
    }

    @Test
    void everyResponseCarriesARequestId() throws Exception {
        mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("[0-9a-f-]{36}")));
        mvc.perform(get("/api/v1/incidents").header(RequestIdFilter.HEADER, "client-req-1"))
                .andExpect(header().string(RequestIdFilter.HEADER, "client-req-1"));
        mvc.perform(get("/api/v1/incidents").header(RequestIdFilter.HEADER, "not a safe value"))
                .andExpect(header().string(RequestIdFilter.HEADER, not("not a safe value")));
    }

    @Test
    void errorBodiesIncludeTheRequestId() throws Exception {
        mvc.perform(post("/api/v1/webhooks/generic").header(ApiKeyAuthenticationFilter.HEADER, tenant.apiKey())
                        .header(RequestIdFilter.HEADER, "req-400")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.requestId").value("req-400"));
        mvc.perform(get("/api/v1/incidents/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.admin().token())
                        .header(RequestIdFilter.HEADER, "req-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.requestId").value("req-404"));
    }

    @Test
    void alertAndIncidentMetricsAreRecorded() {
        double created = counter("opspilot.alerts.received", "integration", "webhook", "outcome", "CREATED_INCIDENT");
        double duplicates = counter("opspilot.alerts.received", "integration", "webhook", "outcome", "DUPLICATE");
        double opened = counter("opspilot.incidents.opened", "severity", "CRITICAL");
        long acknowledged = timerCount("opspilot.incidents.time.to.acknowledge");

        GenericAlertRequest alert = new GenericAlertRequest("evt-1", "payment-api", "HighLatency", Severity.CRITICAL,
                null, null, null);
        UUID incidentId = ingestion.ingest(tenant.tenantId(), "generic", alert).incidentId();
        ingestion.ingest(tenant.tenantId(), "generic", alert);
        incidents.acknowledge(tenant.tenantId(), incidentId, tenant.admin().userId());

        assertThat(counter("opspilot.alerts.received", "integration", "webhook", "outcome", "CREATED_INCIDENT"))
                .isEqualTo(created + 1);
        assertThat(counter("opspilot.alerts.received", "integration", "webhook", "outcome", "DUPLICATE"))
                .isEqualTo(duplicates + 1);
        assertThat(counter("opspilot.incidents.opened", "severity", "CRITICAL")).isEqualTo(opened + 1);
        assertThat(timerCount("opspilot.incidents.time.to.acknowledge")).isEqualTo(acknowledged + 1);
    }

    @Test
    void workRolledBackIsNeverCounted() {
        AtomicInteger runs = new AtomicInteger();

        tx.executeWithoutResult(status -> {
            AfterCommit.run(runs::incrementAndGet);
            status.setRollbackOnly();
        });
        assertThat(runs).hasValue(0);

        tx.executeWithoutResult(status -> AfterCommit.run(runs::incrementAndGet));
        assertThat(runs).hasValue(1);
    }

    /** The gauges' SQL otherwise only runs when Prometheus scrapes, so a broken query would surface in production. */
    @Test
    void everyDatabaseGaugeEvaluates() {
        List<Gauge> gauges = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("opspilot."))
                .filter(Gauge.class::isInstance)
                .map(Gauge.class::cast)
                .toList();

        assertThat(gauges).extracting(gauge -> gauge.getId().getName()).contains(
                "opspilot.incidents.open", "opspilot.outbox.pending", "opspilot.outbox.failed",
                "opspilot.outbox.oldest.pending.age", "opspilot.notifications.pending",
                "opspilot.notifications.failed", "opspilot.escalations.active");
        assertThat(gauges).allSatisfy(gauge -> assertThat(gauge.value())
                .as(gauge.getId().getName()).isNotNaN().isGreaterThanOrEqualTo(0));
    }

    private double counter(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private long timerCount(String name) {
        return registry.find(name).timers().stream().mapToLong(Timer::count).sum();
    }
}
