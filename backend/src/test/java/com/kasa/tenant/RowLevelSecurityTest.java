package com.kasa.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.kasa.ledger.AccountType;
import com.kasa.ledger.LedgerService;
import com.kasa.support.PostgresIntegrationTest;

class RowLevelSecurityTest extends PostgresIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TenantScope tenantScope;

    @Autowired
    LedgerService ledger;

    UUID tenantA;
    UUID tenantB;
    UUID accountOfA;

    @BeforeEach
    void createTenants() {
        tenantA = newTenant();
        tenantB = newTenant();
        accountOfA = ledger.createAccount(tenantA, "cash", AccountType.ASSET, "TRY", false).id();
        ledger.createAccount(tenantB, "cash", AccountType.ASSET, "TRY", false);
    }

    @Test
    void aQueryWithNoTenantBoundSeesNothing() {
        assertThat(count("ledger_accounts")).isZero();
        assertThat(count("tenants")).isZero();
        assertThat(owner.sql("SELECT count(*) FROM ledger_accounts").query(Integer.class).single()).isGreaterThan(0);
    }

    @Test
    void aQueryWithoutATenantFilterStillSeesOnlyItsOwnTenant() {
        Integer visible = tx.execute(status -> {
            tenantScope.apply(tenantA);
            return count("ledger_accounts");
        });

        assertThat(visible).isEqualTo(1);
    }

    @Test
    void cannotInsertARowForAnotherTenant() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            tenantScope.apply(tenantA);
            jdbc.sql("""
                            INSERT INTO ledger_accounts (tenant_id, code, type, currency)
                            VALUES (:tenant, 'stolen', 'ASSET', 'TRY')
                            """)
                    .param("tenant", tenantB)
                    .update();
        })).hasStackTraceContaining("row-level security");
    }

    @Test
    void cannotUpdateAnotherTenantsRow() {
        Integer updated = tx.execute(status -> {
            tenantScope.apply(tenantB);
            return jdbc.sql("UPDATE ledger_accounts SET balance = 999 WHERE id = :id")
                    .param("id", accountOfA)
                    .update();
        });

        assertThat(updated).isZero();
        assertThat(ledger.getAccount(tenantA, accountOfA).balance()).isZero();
    }

    @Test
    void theTenantDoesNotLeakToTheNextUserOfAPooledConnection() {
        for (int i = 0; i < 50; i++) {
            tx.executeWithoutResult(status -> tenantScope.apply(tenantA));

            assertThat(count("ledger_accounts")).isZero();
        }
    }

    @Test
    void applicationRoleCannotRewriteTheLedgerOrReassignAnAccount() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_postings SET amount = 1").update())
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM journal_entries").update())
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_accounts SET tenant_id = :t").param("t", tenantB).update())
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void bindingATenantRequiresATransaction() {
        assertThatThrownBy(() -> tenantScope.apply(tenantA)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void guardRefusesAConnectionThatBypassesRowLevelSecurity() {
        new TenantIsolationGuard(jdbc).afterPropertiesSet();

        assertThatThrownBy(() -> new TenantIsolationGuard(owner).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private UUID newTenant() {
        return owner.sql("INSERT INTO tenants (name) VALUES ('test') RETURNING id").query(UUID.class).single();
    }
}
