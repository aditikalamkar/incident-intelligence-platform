package com.opspilot.identity;

import com.opspilot.identity.SessionService.IssuedToken;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The cookie holding the refresh token. HttpOnly: no script can read it, injected ones included.
 * SameSite=Strict: browsers never attach it to a request another site starts. Its path limits it to
 * the auth endpoints, so it does not travel with every API call.
 */
@Component
class SessionCookie {

    static final String NAME = "opspilot_session";
    static final String PATH = "/api/v1/auth";

    private final SessionProperties properties;

    SessionCookie(SessionProperties properties) {
        this.properties = properties;
    }

    /** @return a Set-Cookie header value that expires with the token */
    String set(IssuedToken token) {
        return cookie(token.value()).maxAge(Duration.between(Instant.now(), token.expiresAt())).build().toString();
    }

    /** @return a Set-Cookie header value that deletes the cookie */
    String clear() {
        return cookie("").maxAge(Duration.ZERO).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder cookie(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path(PATH);
    }
}
