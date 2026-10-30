package com.opspilot.identity;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * @param secret base64-encoded HMAC key of at least 256 bits; required, the app refuses to start without it
 * @param ttl    access tokens are short-lived: clients get new ones from their session (see {@link SessionProperties})
 */
@Validated
@ConfigurationProperties("opspilot.security.jwt")
public record JwtProperties(
        @NotBlank String secret,
        @DefaultValue("PT15M") Duration ttl,
        @DefaultValue("opspilot") String issuer) {
}
