package com.opspilot.escalation;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * One level of an escalation policy.
 *
 * @param userIds              who is paged when the incident reaches this level
 * @param escalateAfterMinutes how long to wait for an acknowledgement before paging the next level;
 *                             ignored on the last level
 */
public record EscalationStep(
        @NotEmpty @Size(max = 20) List<@NotNull UUID> userIds,
        @Min(1) @Max(1440) int escalateAfterMinutes) {
}
