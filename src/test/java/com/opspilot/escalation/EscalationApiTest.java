package com.opspilot.escalation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.TestFixtures.TestTenant;
import com.opspilot.TestFixtures.TestUser;
import com.opspilot.identity.ApiKeyAuthenticationFilter;
import com.opspilot.identity.Role;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class EscalationApiTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestFixtures fixtures;

    TestTenant tenant;
    TestUser responder;
    String teamId;

    @BeforeEach
    void setUp() throws Exception {
        tenant = fixtures.tenant();
        responder = fixtures.user(tenant.tenantId(), Role.RESPONDER);
        String team = mvc.perform(json(as(tenant.admin(), post("/api/v1/teams")), "{\"name\": \"Payments\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        teamId = JsonPath.read(team, "$.id");
    }

    @Test
    void adminSetsPolicyThatEveryoneCanRead() throws Exception {
        mvc.perform(json(as(tenant.admin(), put(policyPath())), policy(responder.userId(), 5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].escalateAfterMinutes").value(5));

        mvc.perform(as(responder, get(policyPath())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].userIds[0]").value(responder.userId().toString()));
    }

    @Test
    void policyChangesAreRestrictedAndValidated() throws Exception {
        UUID outsider = fixtures.tenant().admin().userId();

        mvc.perform(json(as(responder, put(policyPath())), policy(responder.userId(), 5)))
                .andExpect(status().isForbidden());
        mvc.perform(json(as(tenant.admin(), put(policyPath())), "{\"steps\": []}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(as(tenant.admin(), put(policyPath())), policy(responder.userId(), 0)))
                .andExpect(status().isBadRequest());
        mvc.perform(json(as(tenant.admin(), put(policyPath())), policy(outsider, 5)))
                .andExpect(status().isNotFound());
        mvc.perform(json(as(tenant.admin(), put("/api/v1/teams/" + UUID.randomUUID() + "/escalation-policy")),
                        policy(responder.userId(), 5)))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminAssignsAlertingServiceToTeam() throws Exception {
        mvc.perform(post("/api/v1/webhooks/generic").header(ApiKeyAuthenticationFilter.HEADER, tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\": \"evt-1\", \"service\": \"checkout-api\", \"alertName\": \"Errors\"}"))
                .andExpect(status().isCreated());
        String services = mvc.perform(as(responder, get("/api/v1/services")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("checkout-api"))
                .andExpect(jsonPath("$[0].teamId").isEmpty())
                .andReturn().getResponse().getContentAsString();
        String serviceId = JsonPath.read(services, "$[0].id");
        String body = "{\"teamId\": \"" + teamId + "\"}";

        mvc.perform(json(as(responder, put("/api/v1/services/" + serviceId + "/team")), body))
                .andExpect(status().isForbidden());
        mvc.perform(json(as(tenant.admin(), put("/api/v1/services/" + serviceId + "/team")), body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(teamId));
    }

    @Test
    void slackWebhookMustPointAtSlack() throws Exception {
        String path = "/api/v1/teams/" + teamId + "/slack-webhook";

        mvc.perform(json(as(tenant.admin(), put(path)), "{\"url\": \"http://169.254.169.254/latest/meta-data\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(json(as(tenant.admin(), put(path)), "{\"url\": \"https://hooks.slack.com/services/T0/B0/abc123\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(as(responder, get("/api/v1/teams")))
                .andExpect(jsonPath("$[0].slackConfigured").value(true));
    }

    private String policyPath() {
        return "/api/v1/teams/" + teamId + "/escalation-policy";
    }

    private static String policy(UUID userId, int escalateAfterMinutes) {
        return """
                {"steps": [{"userIds": ["%s"], "escalateAfterMinutes": %d}]}
                """.formatted(userId, escalateAfterMinutes);
    }

    private static MockHttpServletRequestBuilder as(TestUser user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
