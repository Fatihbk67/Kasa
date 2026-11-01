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

    public record LoginRequest(@NotBlank String email, @NotBlank String password, @NotBlank @Size(max = 16) String code) {
    }

    private final AdminAccountService accounts;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public AdminAccountController(AdminAccountService accounts, RateLimiter rateLimiter, RateLimitProperties limits) {
        this.accounts = accounts;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @PostMapping("/api/v1/public/admin-login")
    public AdminAccountService.AdminToken login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        rateLimiter.check("login-ip:" + ip, limits.loginPerIp());
        rateLimiter.check("admin-login:" + ip + ":" + request.email().trim().toLowerCase(java.util.Locale.ROOT), limits.loginPerIpAndEmail());
        return accounts.login(ip, request.email(), request.password(), request.code().trim());
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

    @GetMapping("/api/v1/admin/audit-log")
    public List<AdminAccountService.AuditEntry> auditLog() {
        return accounts.auditLog();
    }
}
