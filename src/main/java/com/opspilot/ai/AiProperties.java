package com.opspilot.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The investigation assistant. It is off unless an Anthropic API key is set.
 *
 * @param apiKey              Anthropic API key; empty turns the assistant off
 * @param model               the Claude model that investigates
 * @param effort              how much the model thinks before answering: low, medium, high, xhigh or max
 * @param maxConcurrent       investigations sent to Claude at once from this instance; others wait their turn
 * @param dailyLimitPerTenant investigations an organisation can start in any 24 hours, which bounds its cost
 * @param timeout             for one call to the Claude API; a failed call is retried twice
 * @param baseUrl             Claude API address; set only for a proxy or a test server
 */
@ConfigurationProperties("opspilot.ai")
public record AiProperties(
        String apiKey,
        @DefaultValue("claude-opus-5-5") String model,
        @DefaultValue("medium") String effort,
        @DefaultValue("4") int maxConcurrent,
        @DefaultValue("100") int dailyLimitPerTenant,
        @DefaultValue("PT2M") Duration timeout,
        String baseUrl) {
}
