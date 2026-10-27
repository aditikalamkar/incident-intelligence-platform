package com.opspilot.incident;

import com.opspilot.identity.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    public record AssignRequest(@NotNull UUID assigneeId) {
    }

    public record CommentRequest(@NotBlank @Size(max = 5000) String body) {
    }

    private static final int MAX_LIMIT = 200;

    private final IncidentService incidents;

    public IncidentController(IncidentService incidents) {
        this.incidents = incidents;
    }

    @GetMapping
    public List<IncidentResponse> list(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(defaultValue = "50") int limit) {
        return incidents.list(user.tenantId(), status, Math.clamp(limit, 1, MAX_LIMIT));
    }

    @GetMapping("/{id}")
    public IncidentResponse get(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        return incidents.get(user.tenantId(), id);
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineEventResponse> timeline(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        return incidents.timeline(user.tenantId(), id);
    }

    @PostMapping("/{id}/acknowledge")
    public IncidentResponse acknowledge(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        return incidents.acknowledge(user.tenantId(), id, user.userId());
    }

    @PostMapping("/{id}/assign")
    public IncidentResponse assign(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id,
            @Valid @RequestBody AssignRequest request) {
        return incidents.assign(user.tenantId(), id, request.assigneeId(), user.userId());
    }

    @PostMapping("/{id}/resolve")
    public IncidentResponse resolve(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id) {
        return incidents.resolve(user.tenantId(), id, user.userId());
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public TimelineEventResponse comment(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id,
            @Valid @RequestBody CommentRequest request) {
        return incidents.comment(user.tenantId(), id, user.userId(), request.body());
    }
}
