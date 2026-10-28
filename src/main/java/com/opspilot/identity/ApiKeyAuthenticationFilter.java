package com.opspilot.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the API key from {@code X-API-Key}, or from {@code Authorization: Bearer <key>} for senders
 * such as Alertmanager that can only set the Authorization header. It runs on the webhook chain
 * only, so a Bearer value there is always treated as an API key, never as a user JWT.
 * <p>
 * Not a bean on purpose: Spring Boot would otherwise also register it outside the security chain.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private static final String BEARER_PREFIX = "Bearer ";

    private final ApiKeyService apiKeys;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = apiKey(request);
        if (key != null && !key.isBlank()) {
            apiKeys.authenticate(key).ifPresent(principal -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_API"))));
                SecurityContextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }

    private static String apiKey(HttpServletRequest request) {
        String key = request.getHeader(HEADER);
        if (key != null) {
            return key;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
