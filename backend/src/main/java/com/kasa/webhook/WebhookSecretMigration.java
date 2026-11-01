package com.kasa.webhook;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.kasa.security.SecretCipher;
import com.kasa.tenant.TenantScope;

/**
 * Encrypts signing secrets stored before encryption existed. This runs here and not in a database migration,
 * because the key must never reach the database. It is safe on several instances: the update applies only
 * to a value still in plain text.
 */
@Component
public class WebhookSecretMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecretMigration.class);

    private static final int BATCH_SIZE = 200;

    private record Plaintext(UUID id, UUID tenantId, String secret) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TenantScope tenantScope;
    private final SecretCipher cipher;

    public WebhookSecretMigration(JdbcClient jdbc, TransactionTemplate tx, TenantScope tenantScope, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.tenantScope = tenantScope;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        int converted = encryptPlaintextSecrets();
        if (converted > 0) {
            log.info("Encrypted {} webhook signing secrets", converted);
        }
    }

    public int encryptPlaintextSecrets() {
        int converted = 0;
        while (true) {
            List<Plaintext> batch = jdbc.sql("SELECT id, tenant_id, secret FROM find_plaintext_webhook_secrets(:limit)")
                    .param("limit", BATCH_SIZE)
                    .query((rs, rowNum) -> new Plaintext(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("secret")))
                    .list();
            if (batch.isEmpty()) {
                return converted;
            }
            for (Plaintext row : batch) {
                tx.executeWithoutResult(status -> {
                    tenantScope.apply(row.tenantId());
                    jdbc.sql("UPDATE webhook_endpoints SET secret = :encrypted WHERE id = :id AND secret = :plain")
                            .param("encrypted", cipher.encrypt(WebhookSecrets.context(row.tenantId()), row.secret()))
                            .param("id", row.id())
                            .param("plain", row.secret())
                            .update();
                });
                converted++;
            }
        }
    }
}
