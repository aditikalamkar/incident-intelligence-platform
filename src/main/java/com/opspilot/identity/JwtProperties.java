package com.opspilot.identity;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param secret base64-encoded HMAC key of at least 256 bits; required, the app refuses to start without it
 */
@Validated
@ConfigurationProperties("opspilot.security.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @DefaultValue("PT1H") Duration ttl,
        @DefaultValue("opspilot") String issuer) {
}
