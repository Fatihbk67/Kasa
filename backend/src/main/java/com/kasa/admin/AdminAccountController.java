package com.kasa.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.RateLimitProperties;
import com.kasa.security.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
public class AdminAccountController {

    public record CreateRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(min = 12, max = 72) String password) {
    }

    /** A reason is required, because the merchant is shown it and must know what to fix. */
    public record SuspendRequest(@NotBlank @Size(max = 500) String reason) {
    }

    private final AdminAccountService accounts;
    private final MerchantReviewService merchants;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public AdminAccountController(AdminAccountService accounts, MerchantReviewService merchants, RateLimiter rateLimiter,
                                  RateLimitProperties limits) {
        this.merchants = merchants;
        this.accounts = accounts;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @PostMapping("/api/v1/admin/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminAccountService.CreatedAdmin create(Authentication authentication, @Valid @RequestBody CreateRequest request, HttpServletRequest http) {
        return accounts.create(authentication.getName(), http.getRemoteAddr(), request.email(), request.password());
    }

    @GetMapping("/api/v1/admin/accounts")
    public List<AdminAccountService.AdminView> list() {
        return accounts.list();
    }

    @PostMapping("/api/v1/admin/accounts/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(Authentication authentication, @PathVariable UUID id, HttpServletRequest http) {
        UUID acting = authentication instanceof JwtAuthenticationToken jwt ? UUID.fromString(jwt.getToken().getSubject()) : null;
        accounts.disable(authentication.getName(), http.getRemoteAddr(), id, acting);
    }

    @GetMapping("/api/v1/admin/merchants")
    public List<MerchantReviewService.Merchant> merchants(@RequestParam(required = false) String status) {
        if (status != null && !java.util.Set.of("PENDING", "ACTIVE", "SUSPENDED").contains(status)) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown status");
        }
        return merchants.list(status);
    }

    @GetMapping("/api/v1/admin/merchants/{id}/application")
    public org.springframework.http.ResponseEntity<com.kasa.onboarding.MerchantProfileService.Profile> application(
            Authentication authentication, @PathVariable UUID id, HttpServletRequest http) {
        return merchants.application(authentication.getName(), http.getRemoteAddr(), id)
                .map(org.springframework.http.ResponseEntity::ok)
                .orElse(org.springframework.http.ResponseEntity.notFound().build());
    }

    @PostMapping("/api/v1/admin/merchants/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(Authentication authentication, @PathVariable UUID id, HttpServletRequest http) {
        merchants.approve(authentication.getName(), http.getRemoteAddr(), id);
    }

    @PostMapping("/api/v1/admin/merchants/{id}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(Authentication authentication, @PathVariable UUID id, @Valid @RequestBody SuspendRequest request,
                        HttpServletRequest http) {
        merchants.suspend(authentication.getName(), http.getRemoteAddr(), id, request.reason().trim());
    }

    @PostMapping("/api/v1/admin/merchants/{id}/reactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reactivate(Authentication authentication, @PathVariable UUID id, HttpServletRequest http) {
        merchants.reactivate(authentication.getName(), http.getRemoteAddr(), id);
    }

    @GetMapping("/api/v1/admin/audit-log")
    public List<AdminAccountService.AuditEntry> auditLog() {
        return accounts.auditLog();
    }
}
