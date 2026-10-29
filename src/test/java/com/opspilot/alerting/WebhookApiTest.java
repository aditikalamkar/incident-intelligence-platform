package com.opspilot.alerting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.TestFixtures.TestTenant;
import com.opspilot.identity.ApiKeyAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

@IntegrationTest
class WebhookApiTest {

    private static final String BODY = """
            {"eventId": "evt-42", "service": "payment-api", "alertName": "HighLatency", "severity": "CRITICAL"}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestFixtures fixtures;

    TestTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = fixtures.tenant();
    }

    @Test
    void rejectsMissingOrUnknownApiKey() throws Exception {
        mvc.perform(post("/api/v1/webhooks/generic").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/webhooks/generic").header(ApiKeyAuthenticationFilter.HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userTokenCannotCallWebhookAndApiKeyCannotReadIncidents() throws Exception {
        mvc.perform(post("/api/v1/webhooks/generic")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.admin().token())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/incidents").header(ApiKeyAuthenticationFilter.HEADER, tenant.apiKey()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsIncidentThenReportsRetryAsDuplicate() throws Exception {
        mvc.perform(webhook(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome").value("CREATED_INCIDENT"));
        mvc.perform(webhook(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        mvc.perform(get("/api/v1/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + tenant.admin().token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("TRIGGERED"));
    }

    @Test
    void rejectsInvalidPayload() throws Exception {
        mvc.perform(webhook("""
                        {"service": "payment-api", "alertName": "HighLatency"}
                        """))
                .andExpect(status().isBadRequest());
    }

    private RequestBuilder webhook(String body) {
        return post("/api/v1/webhooks/generic")
                .header(ApiKeyAuthenticationFilter.HEADER, tenant.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
