package com.opspilot.escalation;

import com.opspilot.identity.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/escalation-policy")
public class EscalationPolicyController {

    public record PolicyRequest(@NotEmpty @Size(max = 10) List<@Valid @NotNull EscalationStep> steps) {
    }

    private final EscalationPolicyService policies;

    public EscalationPolicyController(EscalationPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    public EscalationPolicyResponse get(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID teamId) {
        return policies.get(user.tenantId(), teamId);
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public EscalationPolicyResponse save(@AuthenticationPrincipal UserPrincipal admin, @PathVariable UUID teamId,
            @Valid @RequestBody PolicyRequest request) {
        return policies.save(admin.tenantId(), teamId, request.steps());
    }
}
