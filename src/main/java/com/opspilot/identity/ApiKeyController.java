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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/api-keys")
@PreAuthorize("hasRole('ADMIN')")
public class ApiKeyController {

    public record CreateApiKeyRequest(@NotBlank @Size(max = 100) String label) {
    }

    private final ApiKeyService apiKeys;

    public ApiKeyController(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedApiKeyResponse create(@AuthenticationPrincipal UserPrincipal admin,
            @Valid @RequestBody CreateApiKeyRequest request) {
        return apiKeys.create(admin.tenantId(), request.label());
    }

    @GetMapping
    public List<ApiKeyResponse> list(@AuthenticationPrincipal UserPrincipal admin) {
        return apiKeys.list(admin.tenantId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal UserPrincipal admin, @PathVariable UUID id) {
        apiKeys.revoke(admin.tenantId(), id);
    }
}
