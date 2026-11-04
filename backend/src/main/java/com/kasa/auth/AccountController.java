package com.kasa.auth;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.CurrentTenant;
import com.kasa.team.RoleRequestService;
import com.kasa.security.RateLimitProperties;
import com.kasa.security.RateLimiter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
public class AccountController {

    public record RenameRequest(@NotBlank @Size(max = 200) String name) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 12, max = 72) String newPassword) {
    }

    public record PasswordRequest(@NotBlank String password) {
    }

    public record CodeRequest(@NotBlank @Size(max = 32) String code) {
    }

    public record DisableTwoFactorRequest(@NotBlank String password, @NotBlank @Size(max = 32) String code) {
    }

    public record TwoFactorEnabled(List<String> recoveryCodes, String accessToken) {
    }

    public record RoleChangeRequest(@NotBlank String role, @NotBlank @Size(max = 500) String reason) {
    }

    private final AuthService authService;
    private final TwoFactorService twoFactor;
    private final RoleRequestService roleRequests;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public AccountController(AuthService authService, TwoFactorService twoFactor, RoleRequestService roleRequests,
                             RateLimiter rateLimiter, RateLimitProperties limits) {
        this.roleRequests = roleRequests;
        this.twoFactor = twoFactor;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @GetMapping("/api/v1/me")
    public AuthService.Me me(Authentication authentication) {
        return authService.me(CurrentTenant.of(authentication), CurrentTenant.userId(authentication));
    }

    @PutMapping("/api/v1/settings/tenant")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rename(Authentication authentication, @Valid @RequestBody RenameRequest request) {
        authService.renameTenant(CurrentTenant.of(authentication), request.name().trim());
    }

    // Limited per user: a stolen session must not be able to guess the current password at full speed.
    @PostMapping("/api/v1/settings/password")
    public AuthService.TokenResponse changePassword(Authentication authentication, @Valid @RequestBody ChangePasswordRequest request) {
        rateLimiter.check("password:" + CurrentTenant.userId(authentication), limits.loginPerIpAndEmail());
        return authService.changePassword(CurrentTenant.of(authentication), CurrentTenant.userId(authentication),
                request.currentPassword(), request.newPassword());
    }

    @GetMapping("/api/v1/settings/role-request")
    public org.springframework.http.ResponseEntity<RoleRequestService.RoleRequest> latestRoleRequest(Authentication authentication) {
        return roleRequests.latest(CurrentTenant.of(authentication), CurrentTenant.userId(authentication))
                .map(org.springframework.http.ResponseEntity::ok)
                .orElse(org.springframework.http.ResponseEntity.noContent().build());
    }

    @PostMapping("/api/v1/settings/role-request")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleRequestService.RoleRequest requestRole(Authentication authentication, @Valid @RequestBody RoleChangeRequest request) {
        return roleRequests.submit(CurrentTenant.of(authentication), CurrentTenant.userId(authentication), request.role(), request.reason().trim());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/settings/role-request")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelRoleRequest(Authentication authentication) {
        roleRequests.cancel(CurrentTenant.of(authentication), CurrentTenant.userId(authentication));
    }

    @GetMapping("/api/v1/settings/two-factor")
    public TwoFactorService.Status twoFactorStatus(Authentication authentication) {
        return twoFactor.status(CurrentTenant.of(authentication), CurrentTenant.userId(authentication));
    }

    @PostMapping("/api/v1/settings/two-factor/setup")
    public TwoFactorService.Setup beginTwoFactor(Authentication authentication, @Valid @RequestBody PasswordRequest request) {
        rateLimiter.check("password:" + CurrentTenant.userId(authentication), limits.loginPerIpAndEmail());
        return twoFactor.beginSetup(CurrentTenant.of(authentication), CurrentTenant.userId(authentication), request.password());
    }

    @PostMapping("/api/v1/settings/two-factor/enable")
    public TwoFactorEnabled enableTwoFactor(Authentication authentication, @Valid @RequestBody CodeRequest request) {
        rateLimiter.check("password:" + CurrentTenant.userId(authentication), limits.loginPerIpAndEmail());
        UUID tenantId = CurrentTenant.of(authentication);
        UUID userId = CurrentTenant.userId(authentication);
        TwoFactorService.EnabledResult enabled = twoFactor.enable(tenantId, userId, request.code().trim());
        return new TwoFactorEnabled(enabled.recoveryCodes(), authService.token(userId, tenantId, enabled.tokenVersion()).accessToken());
    }

    @PostMapping("/api/v1/settings/two-factor/disable")
    public AuthService.TokenResponse disableTwoFactor(Authentication authentication, @Valid @RequestBody DisableTwoFactorRequest request) {
        rateLimiter.check("password:" + CurrentTenant.userId(authentication), limits.loginPerIpAndEmail());
        UUID tenantId = CurrentTenant.of(authentication);
        UUID userId = CurrentTenant.userId(authentication);
        int version = twoFactor.disable(tenantId, userId, request.password(), request.code().trim());
        return authService.token(userId, tenantId, version);
    }
}
