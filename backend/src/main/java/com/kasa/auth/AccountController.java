package com.kasa.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.CurrentTenant;
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

    private final AuthService authService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public AccountController(AuthService authService, RateLimiter rateLimiter, RateLimitProperties limits) {
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
}
