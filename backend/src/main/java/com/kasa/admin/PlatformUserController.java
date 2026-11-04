package com.kasa.admin;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** For the platform operator, not merchants. Access is limited in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin")
public class PlatformUserController {

    private static final Set<String> REQUEST_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "CANCELED");

    public record RoleRequest(@NotNull String role) {
    }

    public record ApproveRequest(@Size(max = 500) String note) {
    }

    /** A reason is required, because the person is shown it and should know why. */
    public record RejectRequest(@NotBlank @Size(max = 500) String note) {
    }

    private final PlatformUserService users;

    public PlatformUserController(PlatformUserService users) {
        this.users = users;
    }

    @GetMapping("/users")
    public List<PlatformUserService.PlatformUser> list(@RequestParam(required = false) String q) {
        if (q != null && q.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q is too long");
        }
        return users.users(q == null || q.isBlank() ? null : q.trim());
    }

    @PostMapping("/users/{id}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(Authentication authentication, @PathVariable UUID id, @Valid @RequestBody RoleRequest request,
                           HttpServletRequest http) {
        users.changeRole(authentication.getName(), http.getRemoteAddr(), id, request.role());
    }

    @PostMapping("/users/{id}/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(Authentication authentication, @PathVariable UUID id, HttpServletRequest http) {
        users.setDisabled(authentication.getName(), http.getRemoteAddr(), id, true);
    }

    @PostMapping("/users/{id}/enable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enable(Authentication authentication, @PathVariable UUID id, HttpServletRequest http) {
        users.setDisabled(authentication.getName(), http.getRemoteAddr(), id, false);
    }

    @GetMapping("/role-requests")
    public List<PlatformUserService.PlatformRoleRequest> requests(@RequestParam(required = false) String status) {
        if (status != null && !REQUEST_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown status");
        }
        return users.requests(status);
    }

    @PostMapping("/role-requests/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(Authentication authentication, @PathVariable UUID id, @Valid @RequestBody(required = false) ApproveRequest request,
                        HttpServletRequest http) {
        String note = request == null || request.note() == null || request.note().isBlank() ? null : request.note().trim();
        users.decide(authentication.getName(), http.getRemoteAddr(), id, true, note);
    }

    @PostMapping("/role-requests/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(Authentication authentication, @PathVariable UUID id, @Valid @RequestBody RejectRequest request,
                       HttpServletRequest http) {
        users.decide(authentication.getName(), http.getRemoteAddr(), id, false, request.note().trim());
    }
}
