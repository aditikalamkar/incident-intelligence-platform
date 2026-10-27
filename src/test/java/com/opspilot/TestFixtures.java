package com.opspilot;

import com.opspilot.identity.ApiKeyService;
import com.opspilot.identity.Role;
import com.opspilot.identity.TokenService;
import com.opspilot.identity.UserPrincipal;
import com.opspilot.identity.UserResponse;
import com.opspilot.identity.UserService;
import com.opspilot.tenant.TenantService;
import java.util.UUID;

/** Creates an isolated tenant per test, through the real services, so tests never see each other's data. */
public class TestFixtures {

    public static final String PASSWORD = "correct-horse-battery";

    public record TestTenant(UUID tenantId, String apiKey, TestUser admin) {
    }

    public record TestUser(UUID userId, String email, String token) {
    }

    private final TenantService tenants;
    private final UserService users;
    private final ApiKeyService apiKeys;
    private final TokenService tokens;

    public TestFixtures(TenantService tenants, UserService users, ApiKeyService apiKeys, TokenService tokens) {
        this.tenants = tenants;
        this.users = users;
        this.apiKeys = apiKeys;
        this.tokens = tokens;
    }

    public TestTenant tenant() {
        UUID tenantId = tenants.create("tenant-" + UUID.randomUUID());
        String apiKey = apiKeys.create(tenantId, "test").key();
        return new TestTenant(tenantId, apiKey, user(tenantId, Role.ADMIN));
    }

    public TestUser user(UUID tenantId, Role role) {
        String email = UUID.randomUUID() + "@example.com";
        UserResponse user = users.create(tenantId, email, "Test " + role, role, PASSWORD);
        String token = tokens.issue(new UserPrincipal(user.id(), tenantId, role)).accessToken();
        return new TestUser(user.id(), email, token);
    }
}
