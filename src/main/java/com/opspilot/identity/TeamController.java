package com.opspilot.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    public record CreateTeamRequest(@NotBlank @Size(max = 200) String name) {
    }

    private final TeamService teams;

    public TeamController(TeamService teams) {
        this.teams = teams;
    }

    @GetMapping
    public List<TeamResponse> list(@AuthenticationPrincipal UserPrincipal user) {
        return teams.list(user.tenantId());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamResponse create(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody CreateTeamRequest request) {
        return teams.create(admin.tenantId(), request.name());
    }

    @PutMapping("/{teamId}/members/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addMember(@AuthenticationPrincipal UserPrincipal admin, @PathVariable UUID teamId,
            @PathVariable UUID userId) {
        teams.addMember(admin.tenantId(), teamId, userId);
    }

    @DeleteMapping("/{teamId}/members/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal UserPrincipal admin, @PathVariable UUID teamId,
            @PathVariable UUID userId) {
        teams.removeMember(admin.tenantId(), teamId, userId);
    }
}
