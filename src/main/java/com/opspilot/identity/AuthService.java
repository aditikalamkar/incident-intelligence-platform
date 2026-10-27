package com.opspilot.identity;

import com.opspilot.identity.UserService.Credentials;
import com.opspilot.tenant.TenantService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserService users;
    private final TenantService tenants;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;

    /** Checked when the email is unknown, so response time does not reveal which emails are registered. */
    private final String unknownUserHash;

    public AuthService(UserService users, TenantService tenants, TokenService tokens, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.tenants = tenants;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public TokenResponse login(String email, String password) {
        Optional<Credentials> credentials = users.findCredentials(email);
        String hash = credentials.map(Credentials::passwordHash).orElse(unknownUserHash);
        boolean matches = passwordEncoder.matches(password, hash);
        if (credentials.isEmpty() || !matches) {
            throw new BadCredentialsException("Invalid email or password");
        }
        return tokens.issue(credentials.get().principal());
    }

    /** Self-service onboarding: creates the company's tenant and makes the person signing up its admin. */
    @Transactional
    public TokenResponse signup(String companyName, String name, String email, String password) {
        UUID tenantId = tenants.create(companyName);
        UserResponse admin = users.create(tenantId, email, name, Role.ADMIN, password);
        return tokens.issue(new UserPrincipal(admin.id(), tenantId, Role.ADMIN));
    }
}
