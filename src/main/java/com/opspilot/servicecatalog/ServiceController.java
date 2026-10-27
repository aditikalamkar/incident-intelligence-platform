package com.opspilot.servicecatalog;

import com.opspilot.identity.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/v1/services")
public class ServiceController {

    public record AssignTeamRequest(@NotNull UUID teamId) {
    }

    private final ServiceCatalog services;

    public ServiceController(ServiceCatalog services) {
        this.services = services;
    }

    @GetMapping
    public List<ServiceResponse> list(@AuthenticationPrincipal UserPrincipal user) {
        return services.list(user.tenantId());
    }

    @PutMapping("/{id}/team")
    @PreAuthorize("hasRole('ADMIN')")
    public ServiceResponse assignTeam(@AuthenticationPrincipal UserPrincipal admin, @PathVariable UUID id,
            @Valid @RequestBody AssignTeamRequest request) {
        return services.assignTeam(admin.tenantId(), id, request.teamId());
    }
}
