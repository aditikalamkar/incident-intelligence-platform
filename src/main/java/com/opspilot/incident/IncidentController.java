package com.opspilot.incident;

import com.opspilot.common.InvalidRequestException;
import com.opspilot.identity.UserPrincipal;
import com.opspilot.servicecatalog.ServiceCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final ServiceCatalog services;

    public IncidentController(IncidentService incidents, ServiceCatalog services) {
        this.incidents = incidents;
        this.services = services;
    }

    /**
     * Searches the caller's incidents, newest first. All filters are optional and combine with AND;
     * multi-valued ones ({@code status}, {@code severity}, {@code serviceId}) accept repeated or
     * comma-separated values.
     *
     * @param teamId   incidents of services owned by this team
     * @param assignee {@code me}, {@code none} (unassigned) or a user ID
     * @param q        words that must each appear in the title or a comment
     */
    @GetMapping
    public IncidentPage search(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) Set<IncidentStatus> status,
            @RequestParam(required = false) Set<Severity> severity,
            @RequestParam(required = false) Set<UUID> serviceId,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) Instant openedAfter,
            @RequestParam(required = false) Instant openedBefore,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        Set<UUID> serviceIds = serviceId;
        if (teamId != null) {
            Set<UUID> owned = services.ownedBy(user.tenantId(), teamId);
            serviceIds = serviceIds == null
                    ? owned
                    : serviceIds.stream().filter(owned::contains).collect(Collectors.toSet());
        }
        boolean unassigned = "none".equals(assignee);
        UUID assigneeId = assignee == null || unassigned ? null : assigneeId(assignee, user);
        return incidents.search(user.tenantId(), new IncidentQuery(status, severity, serviceIds, assigneeId,
                unassigned, openedAfter, openedBefore, q, cursor, Math.clamp(limit, 1, MAX_LIMIT)));
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

    private static UUID assigneeId(String assignee, UserPrincipal user) {
        if ("me".equals(assignee)) {
            return user.userId();
        }
        try {
            return UUID.fromString(assignee);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("assignee must be 'me', 'none' or a user ID");
        }
    }

    @PostMapping("/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public TimelineEventResponse comment(@AuthenticationPrincipal UserPrincipal user, @PathVariable UUID id,
            @Valid @RequestBody CommentRequest request) {
        return incidents.comment(user.tenantId(), id, user.userId(), request.body());
    }
}
