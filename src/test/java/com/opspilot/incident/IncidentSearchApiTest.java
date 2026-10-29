package com.opspilot.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.TestFixtures.TestTenant;
import com.opspilot.TestFixtures.TestUser;
import com.opspilot.alerting.AlertIngestionService;
import com.opspilot.alerting.GenericAlertRequest;
import com.opspilot.identity.Role;
import com.opspilot.identity.TeamService;
import com.opspilot.servicecatalog.ServiceCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Four incidents, created oldest to newest:
 * A: HighLatency on payment-api, CRITICAL, unassigned
 * B: DbConnectionErrors on payment-api, HIGH, acknowledged by Alice, with a comment
 * C: DiskFull on search-api, LOW, resolved, unassigned
 * D: HighLatency on checkout-api, MEDIUM, assigned to Bob
 * payment-api is owned by the Payments team.
 */
@IntegrationTest
class IncidentSearchApiTest {

    private static final String A = "HighLatency on payment-api";
    private static final String B = "DbConnectionErrors on payment-api";
    private static final String C = "DiskFull on search-api";
    private static final String D = "HighLatency on checkout-api";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestFixtures fixtures;

    @Autowired
    AlertIngestionService ingestion;

    @Autowired
    IncidentService incidents;

    @Autowired
    ServiceCatalog services;

    @Autowired
    TeamService teams;

    UUID tenantId;
    TestUser alice;
    TestUser bob;
    UUID teamId;
    UUID incidentC;

    @BeforeEach
    void setUp() {
        TestTenant tenant = fixtures.tenant();
        tenantId = tenant.tenantId();
        alice = fixtures.user(tenantId, Role.RESPONDER);
        bob = fixtures.user(tenantId, Role.RESPONDER);
        teamId = teams.create(tenantId, "Payments").id();
        services.assignTeam(tenantId, services.resolveOrRegister(tenantId, "payment-api"), teamId);

        open("payment-api", "HighLatency", Severity.CRITICAL);
        UUID incidentB = open("payment-api", "DbConnectionErrors", Severity.HIGH);
        incidents.acknowledge(tenantId, incidentB, alice.userId());
        incidents.comment(tenantId, incidentB, alice.userId(), "Connection pool exhausted after deploy 42");
        incidentC = open("search-api", "DiskFull", Severity.LOW);
        incidents.resolve(tenantId, incidentC, bob.userId());
        UUID incidentD = open("checkout-api", "HighLatency", Severity.MEDIUM);
        incidents.assign(tenantId, incidentD, bob.userId(), alice.userId());
    }

    @Test
    void returnsNewestFirstByDefault() throws Exception {
        assertThat(titles()).containsExactly(D, C, B, A);
    }

    @Test
    void statusAndSeverityFiltersCombine() throws Exception {
        assertThat(titles("status", "TRIGGERED,ACKNOWLEDGED", "severity", "CRITICAL", "severity", "HIGH"))
                .containsExactly(B, A);
        assertThat(titles("status", "RESOLVED")).containsExactly(C);
    }

    @Test
    void filtersByAssignee() throws Exception {
        assertThat(titles("assignee", "me")).as("Alice acknowledged B").containsExactly(B);
        assertThat(titles("assignee", bob.userId().toString())).containsExactly(D);
        assertThat(titles("assignee", "none")).containsExactly(C, A);
    }

    @Test
    void filtersByServiceAndOwningTeam() throws Exception {
        String checkout = services.resolveOrRegister(tenantId, "checkout-api").toString();

        assertThat(titles("teamId", teamId.toString())).containsExactly(B, A);
        assertThat(titles("serviceId", checkout)).containsExactly(D);
        assertThat(titles("teamId", teamId.toString(), "serviceId", checkout)).isEmpty();
    }

    @Test
    void textSearchMatchesTitlesAndCommentsCaseInsensitively() throws Exception {
        assertThat(titles("q", "latency")).containsExactly(D, A);
        assertThat(titles("q", "latency checkout")).as("every word must match").containsExactly(D);
        assertThat(titles("q", "pool EXHAUSTED")).as("matched in a comment").containsExactly(B);
        assertThat(titles("q", "100%")).as("% is literal, not a wildcard").isEmpty();
    }

    @Test
    void filtersByOpenedDateRange() throws Exception {
        String openedC = incidents.get(tenantId, incidentC).openedAt().toString();

        assertThat(titles("openedAfter", openedC)).as("inclusive").containsExactly(D, C);
        assertThat(titles("openedBefore", openedC)).as("exclusive").containsExactly(B, A);
    }

    @Test
    void cursorPagesThroughEveryIncidentOnceEvenWhileNewOnesOpen() throws Exception {
        String first = page(alice, "limit", "2");
        String cursor = JsonPath.read(first, "$.nextCursor");
        assertThat(cursor).isNotBlank();

        open("payment-api", "NewProblem", Severity.HIGH);
        String second = page(alice, "limit", "2", "cursor", cursor);

        List<String> seen = new ArrayList<>(JsonPath.read(first, "$.items[*].title"));
        seen.addAll(JsonPath.read(second, "$.items[*].title"));
        assertThat(seen).containsExactly(D, C, B, A);
        assertThat((String) JsonPath.read(second, "$.nextCursor")).isNull();
    }

    @Test
    void rejectsInvalidParameters() throws Exception {
        mvc.perform(as(alice).param("cursor", "garbage"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid cursor"));
        mvc.perform(as(alice).param("assignee", "someone")).andExpect(status().isBadRequest());
        mvc.perform(as(alice).param("status", "NOT_A_STATUS")).andExpect(status().isBadRequest());
        mvc.perform(as(alice).param("q", "a b c d e f")).andExpect(status().isBadRequest());
    }

    @Test
    void neverReturnsAnotherTenantsIncidents() throws Exception {
        TestUser outsider = fixtures.tenant().admin();

        assertThat(titlesSeenBy(outsider)).isEmpty();
        assertThat(titlesSeenBy(outsider, "q", "latency")).isEmpty();
    }

    private UUID open(String service, String alertName, Severity severity) {
        return ingestion.ingest(tenantId, "generic", new GenericAlertRequest(
                UUID.randomUUID().toString(), service, alertName, severity, null, null, null)).incidentId();
    }

    private List<String> titles(String... params) throws Exception {
        return titlesSeenBy(alice, params);
    }

    private List<String> titlesSeenBy(TestUser user, String... params) throws Exception {
        return JsonPath.read(page(user, params), "$.items[*].title");
    }

    /** @param params name/value pairs */
    private String page(TestUser user, String... params) throws Exception {
        MockHttpServletRequestBuilder request = as(user);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder as(TestUser user) {
        return get("/api/v1/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
    }
}
