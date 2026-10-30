package com.opspilot.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.TestFixtures.TestTenant;
import com.opspilot.TestFixtures.TestUser;
import com.opspilot.alerting.AlertIngestionService;
import com.opspilot.alerting.GenericAlertRequest;
import com.opspilot.identity.ApiKeyAuthenticationFilter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@IntegrationTest
class IncidentStreamApiTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    AlertIngestionService ingestion;

    @Autowired
    IncidentService incidents;

    @Autowired
    IncidentChangeListener listener;

    @Autowired
    JdbcClient jdbc;

    TestTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = fixtures.tenant();
    }

    @Test
    void pushesEveryChangeToTheTenantsConnectedUsers() throws Exception {
        MvcResult stream = connect(tenant.admin());
        MvcResult otherTenant = connect(fixtures.tenant().admin());

        UUID incidentId = open("HighLatency");
        awaitEvent(stream, "\"type\":\"INCIDENT_CREATED\"");
        assertThat(content(stream)).contains("\"title\":\"HighLatency on payment-api\"", "\"status\":\"TRIGGERED\"");

        incidents.acknowledge(tenant.tenantId(), incidentId, tenant.admin().userId());
        awaitEvent(stream, "\"type\":\"ACKNOWLEDGED\"");
        assertThat(content(stream)).contains("\"status\":\"ACKNOWLEDGED\"");

        incidents.comment(tenant.tenantId(), incidentId, tenant.admin().userId(), "Rolling back – café 🚀");
        awaitEvent(stream, "\"body\":\"Rolling back – café 🚀\"");

        assertThat(content(otherTenant)).as("another tenant's stream").doesNotContain("event:incident");
    }

    @Test
    void streamEndsWhenTheTokenExpires() throws Exception {
        MvcResult stream = connect(tenant.admin());

        assertThat(stream.getRequest().getAsyncContext().getTimeout())
                .isBetween(Duration.ofMinutes(14).toMillis(), Duration.ofMinutes(15).toMillis());
    }

    @Test
    void asksClientsToReloadAfterTheListenerReconnects() throws Exception {
        MvcResult stream = connect(tenant.admin());

        jdbc.sql("SELECT pg_terminate_backend(:pid)").param("pid", listener.backendPid()).query().listOfRows();

        awaitEvent(stream, "event:resync");
        open("AfterReconnect");
        awaitEvent(stream, "\"title\":\"AfterReconnect on payment-api\"");
    }

    @Test
    void requiresAUserToken() throws Exception {
        mvc.perform(get("/api/v1/incidents/stream")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/incidents/stream").header(ApiKeyAuthenticationFilter.HEADER, tenant.apiKey()))
                .andExpect(status().isUnauthorized());
    }

    /** Opens a stream and waits until it is live. */
    private MvcResult connect(TestUser user) throws Exception {
        MvcResult stream = mvc.perform(get("/api/v1/incidents/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(request().asyncStarted())
                .andReturn();
        awaitEvent(stream, "event:ready");
        // Headers are written together with the first event
        assertThat(stream.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(stream.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
        return stream;
    }

    private UUID open(String alertName) {
        return ingestion.ingest(tenant.tenantId(), "generic", new GenericAlertRequest(
                UUID.randomUUID().toString(), "payment-api", alertName, Severity.HIGH, null, null, null)).incidentId();
    }

    private static void awaitEvent(MvcResult stream, String expected) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(content(stream)).contains(expected));
    }

    private static String content(MvcResult stream) throws UnsupportedEncodingException {
        return stream.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
