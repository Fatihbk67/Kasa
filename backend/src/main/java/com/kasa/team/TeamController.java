package com.kasa.team;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.auth.AuthService;
import com.kasa.security.CurrentTenant;
import com.kasa.security.RateLimitProperties;
import com.kasa.security.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
public class TeamController {

    public record InviteRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank String role) {
    }

    public record RoleRequest(@NotBlank String role) {
    }

    public record AcceptRequest(@NotBlank @Size(min = 12, max = 72) String password) {
    }

    private final TeamService team;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public TeamController(TeamService team, RateLimiter rateLimiter, RateLimitProperties limits) {
        this.team = team;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    @GetMapping("/api/v1/team")
    public TeamService.Team team(Authentication authentication) {
        return team.team(CurrentTenant.of(authentication));
    }

    @PostMapping("/api/v1/team/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public TeamService.CreatedInvitation invite(Authentication authentication, @Valid @RequestBody InviteRequest request) {
        return team.invite(CurrentTenant.of(authentication), CurrentTenant.userId(authentication), request.email(), request.role());
    }

    @DeleteMapping("/api/v1/team/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication authentication, @PathVariable UUID id) {
        team.revokeInvitation(CurrentTenant.of(authentication), id);
    }

    @PutMapping("/api/v1/team/members/{id}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(Authentication authentication, @PathVariable UUID id, @Valid @RequestBody RoleRequest request) {
        team.changeRole(CurrentTenant.of(authentication), CurrentTenant.userId(authentication), id, request.role());
    }

    @DeleteMapping("/api/v1/team/members/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(Authentication authentication, @PathVariable UUID id) {
        team.remove(CurrentTenant.of(authentication), CurrentTenant.userId(authentication), id);
    }

    @GetMapping("/api/v1/public/invitations/{token}")
    public TeamService.PublicInvitation invitation(@PathVariable String token) {
        return team.publicInvitation(token);
    }

    @PostMapping("/api/v1/public/invitations/{token}/accept")
    public AuthService.TokenResponse accept(@PathVariable String token, @Valid @RequestBody AcceptRequest request, HttpServletRequest http) {
        rateLimiter.check("invite-accept:" + http.getRemoteAddr(), limits.registerPerIp());
        return team.accept(token, request.password());
    }
}
