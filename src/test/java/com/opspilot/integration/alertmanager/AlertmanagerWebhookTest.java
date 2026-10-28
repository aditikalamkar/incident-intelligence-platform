package com.opspilot.integration.alertmanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.TestFixtures.TestTenant;
import com.opspilot.identity.ApiKeyAuthenticationFilter;
import com.opspilot.incident.IncidentEventType;
import com.opspilot.incident.IncidentResponse;
import com.opspilot.incident.IncidentService;
import com.opspilot.incident.IncidentStatus;
import com.opspilot.incident.Severity;
import com.opspilot.incident.TimelineEventResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AlertmanagerWebhookTest {

    private static final String PATH = "/api/v1/webhooks/alertmanager";
    private static final String FIRST_EPISODE = "2026-10-28T09:42:00Z";
    private static final String SECOND_EPISODE = "2026-10-28T11:15:00Z";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    IncidentService incidents;

    TestTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = fixtures.tenant();
    }

    @Test
    void alertsFromSeveralInstancesOfOneServiceBecomeOneIncident() throws Exception {
        String response = send(PATH, payload(firing("fp-a", "pod-a"), firing("fp-b", "pod-b")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED_INCIDENT"))
                .andExpect(jsonPath("$.results[1].outcome").value("GROUPED"))
                .andReturn().getResponse().getContentAsString();

        UUID incidentId = incidentId(response, 0);
        assertThat(incidentId(response, 1)).isEqualTo(incidentId);
        IncidentResponse incident = incidents.get(tenant.tenantId(), incidentId);
        assertThat(incident.title()).isEqualTo("HighLatency on payment-api");
        assertThat(incident.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(incident.alertCount()).isEqualTo(2);
    }

    @Test
    void repeatedNotificationIsIdempotent() throws Exception {
        String body = payload(firing("fp-a", "pod-a"));

        send(PATH, body).andExpect(jsonPath("$.results[0].outcome").value("CREATED_INCIDENT"));
        send(PATH, body).andExpect(jsonPath("$.results[0].outcome").value("DUPLICATE"));
    }

    @Test
    void incidentResolvesWhenItsLastFiringAlertClears() throws Exception {
        UUID incidentId = incidentId(send(PATH, payload(firing("fp-a", "pod-a"), firing("fp-b", "pod-b")))
                .andReturn().getResponse().getContentAsString(), 0);

        send(PATH, payload(resolved("fp-a", "pod-a")))
                .andExpect(jsonPath("$.results[0].outcome").value("ALERT_RESOLVED"));
        assertThat(incidents.get(tenant.tenantId(), incidentId).status()).isEqualTo(IncidentStatus.TRIGGERED);

        send(PATH, payload(resolved("fp-b", "pod-b")))
                .andExpect(jsonPath("$.results[0].outcome").value("INCIDENT_RESOLVED"));
        assertThat(incidents.get(tenant.tenantId(), incidentId).status()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incidents.timeline(tenant.tenantId(), incidentId)).extracting(TimelineEventResponse::type)
                .endsWith(IncidentEventType.ALERT_RESOLVED, IncidentEventType.ALERT_RESOLVED, IncidentEventType.RESOLVED);

        send(PATH, payload(resolved("fp-b", "pod-b")))
                .andExpect(jsonPath("$.results[0].outcome").value("DUPLICATE"));
    }

    @Test
    void autoResolveCanBeTurnedOffPerReceiver() throws Exception {
        UUID incidentId = incidentId(send(PATH, payload(firing("fp-a", "pod-a")))
                .andReturn().getResponse().getContentAsString(), 0);

        send(PATH + "?autoResolve=false", payload(resolved("fp-a", "pod-a")))
                .andExpect(jsonPath("$.results[0].outcome").value("ALERT_RESOLVED"));

        assertThat(incidents.get(tenant.tenantId(), incidentId).status()).isEqualTo(IncidentStatus.TRIGGERED);
    }

    @Test
    void alertFiringAgainAfterResolutionOpensNewIncident() throws Exception {
        UUID first = incidentId(send(PATH, payload(firing("fp-a", "pod-a")))
                .andReturn().getResponse().getContentAsString(), 0);
        send(PATH, payload(resolved("fp-a", "pod-a")))
                .andExpect(jsonPath("$.results[0].outcome").value("INCIDENT_RESOLVED"));

        String again = send(PATH, payload(alert("firing", "fp-a", "pod-a", SECOND_EPISODE)))
                .andExpect(jsonPath("$.results[0].outcome").value("CREATED_INCIDENT"))
                .andReturn().getResponse().getContentAsString();

        assertThat(incidentId(again, 0)).isNotEqualTo(first);
    }

    @Test
    void resolutionOfAnAlertNeverSeenFiringIsIgnored() throws Exception {
        send(PATH, payload(resolved("fp-unknown", "pod-a")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("UNKNOWN_ALERT"))
                .andExpect(jsonPath("$.results[0].incidentId").isEmpty());
    }

    @Test
    void acceptsApiKeyAsBearerTokenAndRejectsBadRequests() throws Exception {
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON).content(payload(firing("fp-a", "pod-a"))))
                .andExpect(status().isOk());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(payload(firing("fp-a", "pod-a"))))
                .andExpect(status().isUnauthorized());
        send(PATH, payload(firing("fp-a", "pod-a").replace("\"alertname\":\"HighLatency\",", "")))
                .andExpect(status().isBadRequest());
        send(PATH, "{\"alerts\": []}")
                .andExpect(status().isBadRequest());
    }

    private ResultActions send(String path, String body) throws Exception {
        return mvc.perform(post(path).header(ApiKeyAuthenticationFilter.HEADER, tenant.apiKey())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static UUID incidentId(String response, int index) {
        return UUID.fromString(JsonPath.read(response, "$.results[" + index + "].incidentId"));
    }

    /** Shaped like a real Alertmanager v4 notification, including fields OpsPilot ignores. */
    private static String payload(String... alerts) {
        return """
                {"version":"4","groupKey":"{}:{alertname=HighLatency}","truncatedAlerts":0,"status":"firing",
                 "receiver":"opspilot","groupLabels":{"alertname":"HighLatency"},"commonLabels":{},
                 "commonAnnotations":{},"externalURL":"http://alertmanager:9093","alerts":[%s]}
                """.formatted(String.join(",", alerts));
    }

    private static String firing(String fingerprint, String instance) {
        return alert("firing", fingerprint, instance, FIRST_EPISODE);
    }

    private static String resolved(String fingerprint, String instance) {
        return alert("resolved", fingerprint, instance, FIRST_EPISODE);
    }

    private static String alert(String status, String fingerprint, String instance, String startsAt) {
        return """
                {"status":"%s","labels":{"alertname":"HighLatency","service":"payment-api","severity":"critical","instance":"%s"},
                 "annotations":{"summary":"p95 latency above 2s"},"startsAt":"%s","endsAt":"0001-01-01T00:00:00Z",
                 "generatorURL":"http://prometheus:9090/graph?g0.expr=latency","fingerprint":"%s"}
                """.formatted(status, instance, startsAt, fingerprint);
    }
}
