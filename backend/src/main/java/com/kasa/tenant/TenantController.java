package com.kasa.tenant;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kasa.security.CurrentTenant;

@RestController
public class TenantController {

    public record TenantResponse(UUID id, String name) {
    }

    private final JdbcClient jdbc;
    private final TenantScope tenantScope;

    public TenantController(JdbcClient jdbc, TenantScope tenantScope) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
    }

    @GetMapping("/api/v1/tenant")
    @Transactional(readOnly = true)
    public TenantResponse current(Authentication authentication) {
        tenantScope.apply(CurrentTenant.of(authentication));
        return jdbc.sql("SELECT id, name FROM tenants")
                .query((rs, rowNum) -> new TenantResponse(rs.getObject("id", UUID.class), rs.getString("name")))
                .single();
    }
}
