package com.opspilot.ai;

import static com.opspilot.ai.TestInvestigator.answer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.opspilot.IntegrationTest;
import com.opspilot.TestFixtures;
import com.opspilot.TestFixtures.TestTenant;
import com.opspilot.TestFixtures.TestUser;
import com.opspilot.ai.Analysis.Point;
import com.opspilot.alerting.AlertIngestionService;
import com.opspilot.alerting.GenericAlertRequest;
import com.opspilot.alerting.IngestionResult;
import com.opspilot.identity.Role;
import com.opspilot.incident.IncidentEventType;
import com.opspilot.incident.IncidentService;
import com.opspilot.incident.Severity;
import com.opspilot.incident.TimelineEventResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class InvestigationApiTest {

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
    TestInvestigator investigator;

    @Autowired
    JdbcClient jdbc;

    TestTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = fixtures.tenant();
        investigator.reset();
    }

    @Test
    void saysTheAssistantIsOn() throws Exception {
        mvc.perform(get("/api/v1/assistant").header(HttpHeaders.AUTHORIZATION, bearer(tenant.admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.model").value(TestInvestigator.MODEL));
    }

    @Test
    void investigatesFromTheAlertsTimelineAndEarlierIncidentsAndLinksItsSources() throws Exception {
        UUID admin = tenant.admin().userId();
        UUID earlier = open("DiskFull").incidentId();
        incidents.comment(tenant.tenantId(), earlier, admin, "Freed space by rotating logs");
        incidents.resolve(tenant.tenantId(), earlier, admin);
        IngestionResult opened = open("HighLatency");
        UUID incidentId = opened.incidentId();
        TimelineEventResponse comment = incidents.comment(tenant.tenantId(), incidentId, admin,
                "p99 doubled after the 14:05 deploy");
        investigator.answerWith(briefing -> answer(new Analysis("Latency doubled after a deploy.",
                List.of(new Point("It started with the deploy.", List.of("E2", "A1", "E99"))),
                List.of(new Point("Roll back, as last time.", List.of("P1"))))));

        String started = start(incidentId, tenant.admin());
        assertThat(JsonPath.<String>read(started, "$.status")).isEqualTo("RUNNING");
        String id = JsonPath.read(started, "$.id");
        String done = awaitStatus(incidentId, id, "COMPLETED");

        assertThat(JsonPath.<String>read(done, "$.result.summary")).isEqualTo("Latency doubled after a deploy.");
        assertThat(JsonPath.<List<String>>read(done, "$.result.findings[0].sources[*].kind"))
                .as("E99 was not in the briefing").containsExactly("EVENT", "ALERT");
        assertThat(JsonPath.<List<String>>read(done, "$.result.findings[0].sources[*].id"))
                .containsExactly(comment.id().toString(), opened.alertId().toString());
        assertThat(JsonPath.<List<String>>read(done, "$.result.findings[0].sources[*].label"))
                .containsExactly("Comment by Test ADMIN", "HighLatency");
        assertThat(JsonPath.<String>read(done, "$.result.nextSteps[0].sources[0].id")).isEqualTo(earlier.toString());
        assertThat(JsonPath.<String>read(done, "$.model")).isEqualTo(TestInvestigator.MODEL);
        assertThat(JsonPath.<String>read(done, "$.requestedBy")).isEqualTo(admin.toString());

        assertThat(investigator.briefings()).singleElement().extracting(Briefing::text).asString()
                .contains("p99 doubled after the 14:05 deploy")
                .contains("[P1] \"DiskFull on payment-api\"", "Freed space by rotating logs");

        TimelineEventResponse announced = incidents.timeline(tenant.tenantId(), incidentId).getLast();
        assertThat(announced.type()).isEqualTo(IncidentEventType.INVESTIGATION_COMPLETED);
        assertThat(announced.actorId()).isEqualTo(admin);
        assertThat(announced.details().get("investigationId").asString()).isEqualTo(id);

        assertThat(jdbc.sql("SELECT input_tokens, output_tokens FROM investigation WHERE id = :id")
                .param("id", UUID.fromString(id))
                .query((rs, row) -> List.of(rs.getLong(1), rs.getLong(2)))
                .single()).containsExactly(1200L, 300L);
    }

    @Test
    void aSecondRequestJoinsTheRunningInvestigation() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        investigator.answerWith(briefing -> {
            try {
                release.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return answer(new Analysis("Done.", List.of(), List.of()));
        });
        UUID incidentId = open("HighLatency").incidentId();
        TestUser responder = fixtures.user(tenant.tenantId(), Role.RESPONDER);

        String id;
        try {
            String first = start(incidentId, tenant.admin());
            String second = start(incidentId, responder);
            id = JsonPath.read(first, "$.id");
            assertThat(JsonPath.<String>read(second, "$.id")).isEqualTo(id);
            assertThat(JsonPath.<String>read(second, "$.requestedBy")).isEqualTo(tenant.admin().userId().toString());
        } finally {
            release.countDown();
        }

        awaitStatus(incidentId, id, "COMPLETED");
        assertThat(investigator.briefings()).hasSize(1);
    }

    @Test
    void aFailureIsShownOnTheTimelineAndCanBeRetried() throws Exception {
        investigator.answerWith(briefing -> {
            throw new InvestigationFailedException("Claude is temporarily unavailable. Try again in a few minutes.");
        });
        UUID incidentId = open("HighLatency").incidentId();

        String failedId = JsonPath.read(start(incidentId, tenant.admin()), "$.id");
        String failed = awaitStatus(incidentId, failedId, "FAILED");

        assertThat(JsonPath.<String>read(failed, "$.failure"))
                .isEqualTo("Claude is temporarily unavailable. Try again in a few minutes.");
        assertThat(JsonPath.<Object>read(failed, "$.result")).isNull();
        assertThat(incidents.timeline(tenant.tenantId(), incidentId).getLast().type())
                .isEqualTo(IncidentEventType.INVESTIGATION_FAILED);

        investigator.reset();
        String retryId = JsonPath.read(start(incidentId, tenant.admin()), "$.id");
        assertThat(retryId).isNotEqualTo(failedId);
        awaitStatus(incidentId, retryId, "COMPLETED");
    }

    @Test
    void anUnexpectedErrorIsShownWithoutItsDetails() throws Exception {
        investigator.answerWith(briefing -> {
            throw new IllegalStateException("connection refused: internal-host:5432");
        });
        UUID incidentId = open("HighLatency").incidentId();

        String id = JsonPath.read(start(incidentId, tenant.admin()), "$.id");

        assertThat(JsonPath.<String>read(awaitStatus(incidentId, id, "FAILED"), "$.failure"))
                .isEqualTo("The assistant ran into an unexpected error. Try again.");
    }

    @Test
    void limitsHowManyInvestigationsAnOrganisationRunsPerDay() throws Exception {
        UUID incidentId = open("HighLatency").incidentId();
        for (int i = 0; i < 3; i++) { // the test limit
            awaitStatus(incidentId, JsonPath.read(start(incidentId, tenant.admin()), "$.id"), "COMPLETED");
        }

        mvc.perform(post("/api/v1/incidents/{id}/investigations", incidentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant.admin())))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(containsString("3 assistant investigations")));
    }

    @Test
    void anIncidentWithoutInvestigationsHasNoLatest() throws Exception {
        UUID incidentId = open("HighLatency").incidentId();

        mvc.perform(get("/api/v1/incidents/{id}/investigations/latest", incidentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tenant.admin())))
                .andExpect(status().isNoContent());
    }

    @Test
    void anotherOrganisationsIncidentIsNotFound() throws Exception {
        UUID incidentId = open("HighLatency").incidentId();
        TestUser outsider = fixtures.tenant().admin();

        mvc.perform(post("/api/v1/incidents/{id}/investigations", incidentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/incidents/{id}/investigations/latest", incidentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsider)))
                .andExpect(status().isNotFound());
        assertThat(investigator.briefings()).isEmpty();
    }

    private IngestionResult open(String alertName) {
        return ingestion.ingest(tenant.tenantId(), "generic", new GenericAlertRequest(UUID.randomUUID().toString(),
                "payment-api", alertName, Severity.HIGH, "p99 latency above 2s", null, Map.of("region", "eu-west-1")));
    }

    /** @return the response body: the new or running investigation */
    private String start(UUID incidentId, TestUser user) throws Exception {
        return mvc.perform(post("/api/v1/incidents/{id}/investigations", incidentId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** @return the latest investigation's body, once it is the given one and has the given status */
    private String awaitStatus(UUID incidentId, String investigationId, String expected) {
        return await().atMost(TIMEOUT).until(() -> {
            String body = mvc.perform(get("/api/v1/incidents/{id}/investigations/latest", incidentId)
                            .header(HttpHeaders.AUTHORIZATION, bearer(tenant.admin())))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            return investigationId.equals(JsonPath.read(body, "$.id"))
                    && expected.equals(JsonPath.read(body, "$.status")) ? body : null;
        }, body -> body != null);
    }

    private static String bearer(TestUser user) {
        return "Bearer " + user.token();
    }
}
