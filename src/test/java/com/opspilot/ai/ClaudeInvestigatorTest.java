package com.opspilot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs the real SDK against a local server that plays the Claude API, to check what is sent and how answers are read. */
class ClaudeInvestigatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Briefing BRIEFING = new Briefing("Investigate this incident.", Map.of(), Instant.now());

    record Request(String path, Map<String, List<String>> headers, JsonNode body) {

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(header -> header.getKey().equalsIgnoreCase(name))
                    .map(header -> String.join(",", header.getValue()))
                    .findFirst()
                    .orElse(null);
        }
    }

    record Reply(int status, Map<String, String> headers, String body) {
    }

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Deque<Reply> replies = new ArrayDeque<>();
    private HttpServer server;
    private ClaudeInvestigator investigator;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        investigator = new ClaudeInvestigator(new AiProperties("test-key", "claude-opus-5-5", "medium", 1, 10,
                Duration.ofSeconds(10), "http://localhost:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stop() {
        investigator.close();
        server.stop(0);
    }

    @Test
    void sendsOneStructuredRequestWithServerSideFallbacks() {
        replies.add(message("end_turn", """
                {"summary": "Checkout latency doubled after a deploy.",
                 "findings": [{"text": "p99 rose at 14:05, when the deploy finished.", "sources": ["E2", "A1"]}],
                 "nextSteps": [{"text": "Roll back the deploy.", "sources": []}]}
                """));

        Investigator.Answer answer = investigator.investigate(BRIEFING);

        assertThat(answer.analysis().summary()).isEqualTo("Checkout latency doubled after a deploy.");
        assertThat(answer.analysis().findings()).containsExactly(
                new Analysis.Point("p99 rose at 14:05, when the deploy finished.", List.of("E2", "A1")));
        assertThat(answer.model()).isEqualTo("claude-opus-5-5");
        assertThat(answer.inputTokens()).isEqualTo(1500);
        assertThat(answer.outputTokens()).isEqualTo(420);

        assertThat(requests).hasSize(1);
        Request request = requests.getFirst();
        assertThat(request.path()).startsWith("/v1/messages");
        // API version 2026-10-21: API keys go in the Authorization header, not X-Api-Key
        assertThat(request.header("authorization")).isEqualTo("Bearer test-key");
        assertThat(request.header("anthropic-version")).isEqualTo("2026-10-21");
        assertThat(request.header("anthropic-beta")).contains("server-side-fallback-2026-07-01");
        JsonNode body = request.body();
        assertThat(body.path("model").asString()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("fallbacks").asString()).isEqualTo("default");
        assertThat(body.path("output_config").path("effort").asString()).isEqualTo("medium");
        assertThat(body.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
        assertThat(body.path("output_config").path("format").path("schema").path("properties").propertyNames())
                .containsExactlyInAnyOrder("summary", "findings", "nextSteps");
        assertThat(body.path("system").asString()).startsWith("You are the investigation assistant in OpsPilot");
        assertThat(body.path("messages").get(0).path("content").asString()).isEqualTo("Investigate this incident.");
        // Thinking is left to the model's default (adaptive); sampling parameters are not accepted by current models
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("temperature")).isFalse();
    }

    @Test
    void reportsARefusal() {
        replies.add(new Reply(200, Map.of(), """
                {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5", "content": [],
                 "stop_reason": "refusal", "stop_sequence": null,
                 "stop_details": {"type": "refusal", "category": null, "explanation": null},
                 "usage": {"input_tokens": 1500, "output_tokens": 0}}
                """));

        assertThatThrownBy(() -> investigator.investigate(BRIEFING))
                .isInstanceOf(InvestigationFailedException.class)
                .hasMessage("Claude declined to investigate this incident.");
    }

    @Test
    void retriesWhenRateLimitedThenSaysSo() {
        for (int i = 0; i < 3; i++) {
            replies.add(error(429, "rate_limit_error", Map.of("retry-after-ms", "10")));
        }

        assertThatThrownBy(() -> investigator.investigate(BRIEFING))
                .isInstanceOf(InvestigationFailedException.class)
                .hasMessageContaining("too many requests");
        assertThat(requests).as("the first attempt and two retries").hasSize(3);
    }

    @Test
    void reportsARejectedApiKeyWithoutRetrying() {
        replies.add(error(401, "authentication_error", Map.of()));

        assertThatThrownBy(() -> investigator.investigate(BRIEFING))
                .isInstanceOf(InvestigationFailedException.class)
                .hasMessageContaining("API key was rejected");
        assertThat(requests).hasSize(1);
    }

    @Test
    void reportsAnAnswerThatIsNotTheRequestedJson() {
        replies.add(message("end_turn", "Sorry, here is some prose instead."));

        assertThatThrownBy(() -> investigator.investigate(BRIEFING))
                .isInstanceOf(InvestigationFailedException.class)
                .hasMessage("Claude's answer could not be read. Try again.");
    }

    @Test
    void reportsAnAnswerCutOffByTheTokenLimit() {
        replies.add(message("max_tokens", "{\"summary\": \"Checkout lat"));

        assertThatThrownBy(() -> investigator.investigate(BRIEFING))
                .isInstanceOf(InvestigationFailedException.class)
                .hasMessageContaining("cut off");
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.add(new Request(exchange.getRequestURI().toString(), Map.copyOf(exchange.getRequestHeaders()),
                JSON.readTree(exchange.getRequestBody().readAllBytes())));
        Reply reply = replies.isEmpty() ? error(500, "api_error", Map.of()) : replies.poll();
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        reply.headers().forEach(exchange.getResponseHeaders()::add);
        exchange.sendResponseHeaders(reply.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static Reply message(String stopReason, String text) {
        return new Reply(200, Map.of(), JSON.writeValueAsString(Map.of(
                "id", "msg_1",
                "type", "message",
                "role", "assistant",
                "model", "claude-opus-5-5",
                "content", List.of(Map.of("type", "text", "text", text)),
                "stop_reason", stopReason,
                "usage", Map.of("input_tokens", 1500, "output_tokens", 420))));
    }

    private static Reply error(int status, String type, Map<String, String> headers) {
        return new Reply(status, headers,
                "{\"type\": \"error\", \"error\": {\"type\": \"" + type + "\", \"message\": \"simulated\"}}");
    }
}
