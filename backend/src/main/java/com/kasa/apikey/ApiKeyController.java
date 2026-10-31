package com.kasa.apikey;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.CurrentTenant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

    public record CreateApiKeyRequest(@NotBlank @Size(max = 100) String name) {
    }

    private final ApiKeyService apiKeys;

    public ApiKeyController(ApiKeyService apiKeys) {
        this.apiKeys = apiKeys;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiKeyService.CreatedApiKey create(Authentication authentication,
                                              @Valid @RequestBody CreateApiKeyRequest request) {
        return apiKeys.create(CurrentTenant.of(authentication), request.name());
    }

    @GetMapping
    public List<ApiKeyService.ApiKeySummary> list(Authentication authentication) {
        return apiKeys.list(CurrentTenant.of(authentication));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication authentication, @PathVariable UUID id) {
        apiKeys.revoke(CurrentTenant.of(authentication), id);
    }
}
