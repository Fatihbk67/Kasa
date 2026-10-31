package com.kasa.tenant;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Superusers and table owners silently skip RLS, so such a connection must stop the application from starting. */
@Component
public class TenantIsolationGuard implements InitializingBean {

    private final JdbcClient jdbc;

    public TenantIsolationGuard(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        boolean bypassesRls = jdbc.sql("""
                        SELECT r.rolsuper OR r.rolbypassrls OR pg_has_role(current_user, c.relowner, 'USAGE')
                        FROM pg_roles r, pg_class c
                        WHERE r.rolname = current_user AND c.oid = 'public.tenants'::regclass
                        """)
                .query(Boolean.class)
                .single();
        if (bypassesRls) {
            throw new IllegalStateException(
                    "The application's database role bypasses row level security; connect as a restricted role");
        }
    }
}
