package com.opspilot.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** A signed-in engineer, built from a verified JWT. Every data access is scoped by {@link #tenantId()}. */
public record UserPrincipal(UUID userId, UUID tenantId, Role role) {

    List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
