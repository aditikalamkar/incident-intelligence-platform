package com.opspilot.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
class IdentityApiTest {

    private static final String ALERT = """
            {"eventId": "evt-1", "service": "payment-api", "alertName": "HighLatency"}
            """;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestFixtures fixtures;

    TestTenant tenant;
    TestUser responder;

    @BeforeEach
    void setUp() {
        tenant = fixtures.tenant();
        responder = fixtures.user(tenant.tenantId(), Role.RESPONDER);
    }

    @Test
    void signupCreatesTenantWithAdminAndRejectsTakenEmail() throws Exception {
        String email = UUID.randomUUID() + "@startup.io";
        String body = """
                {"companyName": "Acme", "name": "Ada", "email": "%s", "password": "a-long-password"}
                """.formatted(email);

        String response = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(response, "$.accessToken");

        mvc.perform(as(token, get("/api/v1/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace(email, email.toUpperCase())))
                .andExpect(status().isConflict());
    }

    @Test
    void loginChecksPassword() throws Exception {
        mvc.perform(login(responder.email(), TestFixtures.PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
        mvc.perform(login(responder.email(), "wrong-password"))
                .andExpect(status().isUnauthorized());
        mvc.perform(login("nobody@example.com", TestFixtures.PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMissingOrTamperedToken() throws Exception {
        mvc.perform(get("/api/v1/incidents")).andExpect(status().isUnauthorized());
        mvc.perform(as(responder.token() + "x", get("/api/v1/incidents"))).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAdminsManageUsersAndApiKeys() throws Exception {
        String newUser = """
                {"email": "%s", "name": "Grace", "role": "RESPONDER", "password": "a-long-password"}
                """.formatted(UUID.randomUUID() + "@example.com");

        mvc.perform(as(responder.token(), post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON).content(newUser))
                .andExpect(status().isForbidden());
        mvc.perform(as(responder.token(), get("/api/v1/api-keys")))
                .andExpect(status().isForbidden());
        mvc.perform(as(tenant.admin().token(), post("/api/v1/users")).contentType(MediaType.APPLICATION_JSON)
                        .content(newUser))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("RESPONDER"));
    }

    @Test
    void createdApiKeyWorksUntilRevoked() throws Exception {
        String response = mvc.perform(as(tenant.admin().token(), post("/api/v1/api-keys"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\": \"grafana\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(response, "$.key");
        String id = JsonPath.read(response, "$.id");

        mvc.perform(webhook(key)).andExpect(status().isCreated());
        mvc.perform(as(tenant.admin().token(), delete("/api/v1/api-keys/" + id)))
                .andExpect(status().isNoContent());
        mvc.perform(webhook(key)).andExpect(status().isUnauthorized());
    }

    @Test
    void teamsOnlyAcceptMembersFromSameTenant() throws Exception {
        String response = mvc.perform(as(tenant.admin().token(), post("/api/v1/teams"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Payments\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String teamId = JsonPath.read(response, "$.id");
        UUID outsider = fixtures.tenant().admin().userId();

        mvc.perform(as(tenant.admin().token(), put("/api/v1/teams/" + teamId + "/members/" + responder.userId())))
                .andExpect(status().isNoContent());
        mvc.perform(as(tenant.admin().token(), put("/api/v1/teams/" + teamId + "/members/" + outsider)))
                .andExpect(status().isNotFound());
        mvc.perform(as(responder.token(), get("/api/v1/teams")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].memberIds.length()").value(1))
                .andExpect(jsonPath("$[0].memberIds[0]").value(responder.userId().toString()));
    }

    @Test
    void usersListIsScopedToTenant() throws Exception {
        fixtures.tenant();

        mvc.perform(as(responder.token(), get("/api/v1/users")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder login(String email, String password) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password));
    }

    private static MockHttpServletRequestBuilder webhook(String apiKey) {
        return post("/api/v1/webhooks/generic").header(ApiKeyAuthenticationFilter.HEADER, apiKey)
                .contentType(MediaType.APPLICATION_JSON).content(ALERT);
    }
}
