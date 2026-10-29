package com.opspilot.identity;

import java.time.Instant;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;

/**
 * A signed-in engineer. The principal is the {@link UserPrincipal}; the verified JWT is kept as the
 * token (it is not a credential that gets erased after login), so its expiry stays known, e.g. to end
 * a live update stream when the token runs out.
 */
public final class UserAuthentication extends AbstractOAuth2TokenAuthenticationToken<Jwt> {

    UserAuthentication(Jwt jwt, UserPrincipal user) {
        super(jwt, user, jwt, user.authorities());
        setAuthenticated(true);
    }

    public UserPrincipal user() {
        return (UserPrincipal) getPrincipal();
    }

    public Instant expiresAt() {
        return getToken().getExpiresAt();
    }

    @Override
    public Map<String, Object> getTokenAttributes() {
        return getToken().getClaims();
    }
}
