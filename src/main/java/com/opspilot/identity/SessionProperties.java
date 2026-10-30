package com.opspilot.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sign-in sessions, kept alive by a rotating refresh token in an HttpOnly cookie (see ADR 0006).
 *
 * @param idleTimeout      a session not refreshed for this long ends: each refresh token expires after it
 * @param maxLifetime      a session ends this long after sign-in, however active it was
 * @param reuseGracePeriod a token presented again this soon after it was rotated is taken as a second
 *                         request from the same browser racing the first, not as a stolen copy
 * @param secureCookie     send the cookie over HTTPS only; turn off only for local development over HTTP
 */
@ConfigurationProperties("opspilot.security.session")
public record SessionProperties(
        @DefaultValue("P7D") Duration idleTimeout,
        @DefaultValue("P30D") Duration maxLifetime,
        @DefaultValue("PT10S") Duration reuseGracePeriod,
        @DefaultValue("true") boolean secureCookie) {
}
