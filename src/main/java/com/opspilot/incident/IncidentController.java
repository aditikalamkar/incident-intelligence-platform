package com.opspilot.incident;

import com.opspilot.tenant.TenantPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private static final int MAX_LIMIT = 200;

    private final IncidentService incidents;

    public IncidentController(IncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    List<IncidentResponse> list(@AuthenticationPrincipal TenantPrincipal principal,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(defaultValue = "50") int limit) {
        return incidents.list(principal.tenantId(), status, Math.clamp(limit, 1, MAX_LIMIT));
    }

    @GetMapping("/{id}")
    IncidentResponse get(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
        return incidents.get(principal.tenantId(), id);
    }

    @GetMapping("/{id}/timeline")
    List<TimelineEventResponse> timeline(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
        return incidents.timeline(principal.tenantId(), id);
    }

    @PostMapping("/{id}/acknowledge")
    IncidentResponse acknowledge(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
        return incidents.acknowledge(principal.tenantId(), id);
    }

    @PostMapping("/{id}/resolve")
    IncidentResponse resolve(@AuthenticationPrincipal TenantPrincipal principal, @PathVariable UUID id) {
        return incidents.resolve(principal.tenantId(), id);
    }
}
