package com.opspilot.integration.alertmanager;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The parts of Prometheus Alertmanager's webhook payload (version 4) that OpsPilot uses. Group-level
 * fields such as {@code groupLabels} are ignored: each alert is processed on its own.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerWebhook(@NotEmpty List<@Valid @NotNull Alert> alerts) {

    /**
     * @param fingerprint Alertmanager's stable ID for the alert's label set
     * @param startsAt    when this firing episode began; with the fingerprint, it identifies the episode
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(
            @NotNull @Pattern(regexp = "firing|resolved") String status,
            @NotNull Map<String, String> labels,
            Map<String, String> annotations,
            @NotNull Instant startsAt,
            String generatorURL,
            @NotBlank @Size(max = 100) String fingerprint) {

        @JsonIgnore
        @AssertTrue(message = "labels.alertname is required")
        public boolean isNamed() {
            return labels != null && labels.get("alertname") != null && !labels.get("alertname").isBlank();
        }

        @JsonIgnore
        public boolean isResolved() {
            return "resolved".equals(status);
        }
    }
}
