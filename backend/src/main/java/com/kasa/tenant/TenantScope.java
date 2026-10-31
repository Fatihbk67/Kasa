package com.kasa.tenant;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Binds the current transaction to a tenant for the database's row level security policies.
 * The setting is transaction-local, so a pooled connection cannot carry it into the next request.
 */
@Component
public class TenantScope {

    private final JdbcClient jdbc;

    public TenantScope(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(UUID tenantId) {
        jdbc.sql("SELECT set_config('app.tenant_id', :tenantId, true)")
                .param("tenantId", tenantId.toString())
                .query(String.class)
                .single();
    }
}
