package com.kasa.support;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * Runs the application against a real PostgreSQL, connected as the restricted role like production.
 * {@link #owner} is a superuser connection for arranging data and asserting past row level security.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

    private static final EmbeddedPostgres POSTGRES = startPostgres();

    protected static final JdbcClient owner = JdbcClient.create(POSTGRES.getPostgresDatabase());

    protected static final TransactionTemplate ownerTx =
            new TransactionTemplate(new JdbcTransactionManager(POSTGRES.getPostgresDatabase()));

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        String url = "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/postgres";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> "kasa_app");
        registry.add("spring.datasource.password", () -> "kasa_app");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 20);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("kasa.jwt.secret", () -> "test-only-secret-test-only-secret-1234");
        // Tests run recovery themselves, at the moment they choose.
        registry.add("kasa.payments.recovery-interval", () -> "PT1H");
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            // initdb rejects non-ASCII OS locale names such as "Turkish_Türkiye.1254".
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().setLocaleConfig("locale", "C").start();
            JdbcClient.create(postgres.getPostgresDatabase())
                    .sql("CREATE ROLE kasa_app LOGIN PASSWORD 'kasa_app' NOSUPERUSER NOBYPASSRLS")
                    .update();
            return postgres;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
