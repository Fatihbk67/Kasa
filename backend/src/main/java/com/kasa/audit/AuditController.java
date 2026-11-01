package com.kasa.audit;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.security.CurrentTenant;

@RestController
@RequestMapping("/api/v1/audit-log")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLog auditLog;

    public AuditController(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    @GetMapping
    public AuditLog.EntryPage list(Authentication authentication,
                                   @RequestParam(required = false) String action,
                                   @RequestParam(required = false) Long before,
                                   @RequestParam(defaultValue = "30") int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        return auditLog.list(CurrentTenant.of(authentication), action == null || action.isBlank() ? null : action, before, limit);
    }

    @GetMapping("/actions")
    public List<String> actions(Authentication authentication) {
        return auditLog.actions(CurrentTenant.of(authentication));
    }

    @GetMapping("/verify")
    public AuditLog.ChainReport verify(Authentication authentication) {
        return auditLog.verify(CurrentTenant.of(authentication));
    }
}
