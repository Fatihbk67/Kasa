package com.kasa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;
import com.kasa.tenant.TenantScope;

class AuditApiTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    AuditLog auditLog;

    @Autowired
    JdbcClient applicationJdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    TenantScope tenantScope;

    TestMerchant merchant;

    @BeforeEach
    void registerMerchant() {
        merchant = TestMerchant.register(port);
    }

    @Test
    void importantActionsAreRecordedWithWhoDidThem() {
        UUID payment = merchant.capturedPayment(1_000);
        merchant.refund(payment, 100);

        List<Map<String, Object>> entries = entries();
        List<Object> actions = entries.stream().map(entry -> entry.get("action")).toList();

        assertThat(actions).contains("TENANT_REGISTERED", "API_KEY_CREATED", "PAYMENT_CAPTURE_REQUESTED", "PAYMENT_REFUND_REQUESTED");
        Map<String, Object> created = entries.stream().filter(entry -> entry.get("action").equals("API_KEY_CREATED")).findFirst().orElseThrow();
        assertThat(created.get("actorType")).isEqualTo("USER");
        assertThat((String) created.get("actorLabel")).contains("@example.com");
        Map<String, Object> capture = entries.stream().filter(entry -> entry.get("action").equals("PAYMENT_CAPTURE_REQUESTED")).findFirst().orElseThrow();
        assertThat(capture.get("actorType")).isEqualTo("API_KEY");
        assertThat(capture.get("targetId")).isEqualTo(payment.toString());
        assertThat((String) capture.get("metadata")).contains("\"amount\":1000");
    }

    @Test
    void secretsNeverEnterTheLog() {
        Map<String, Object> created = merchant.client().post().uri("/api/v1/api-keys")
                .header("Authorization", "Bearer " + merchant.token())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "gizli"))
                .retrieve().body(Map.class);

        String stored = owner.sql("SELECT string_agg(metadata || coalesce(actor_label, ''), ' ') FROM audit_log WHERE tenant_id = :t")
                .param("t", merchant.tenantId()).query(String.class).single();

        assertThat(stored).doesNotContain((String) created.get("key")).doesNotContain("password");
    }

    @Test
    void aHealthyChainVerifies() {
        merchant.capturedPayment(1_000);

        Map<String, Object> report = verify();

        assertThat(report).containsEntry("valid", true);
        assertThat(((Number) report.get("checked")).longValue()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void concurrentActionsStillFormOneUnbrokenChain() throws Exception {
        List<UUID> payments = Concurrency.run(20, i -> () -> merchant.capturedPayment(1_000));

        assertThat(payments).hasSize(20);
        assertThat(verify()).containsEntry("valid", true);
        assertThat(owner.sql("SELECT count(*) = max(seq) FROM audit_log WHERE tenant_id = :t")
                .param("t", merchant.tenantId()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void editingAPastRecordIsDetectedEvenWithSuperuserRights() {
        merchant.capturedPayment(1_000);
        merchant.capturedPayment(2_000);

        tamper("UPDATE audit_log SET metadata = '{\"amount\":1}' WHERE tenant_id = :t AND seq = 3");

        assertThat(verify()).containsEntry("valid", false).containsEntry("brokenAtSeq", 3);
    }

    @Test
    void removingAPastRecordIsDetected() {
        merchant.capturedPayment(1_000);
        merchant.capturedPayment(2_000);

        tamper("DELETE FROM audit_log WHERE tenant_id = :t AND seq = 2");

        assertThat(verify()).containsEntry("valid", false).containsEntry("brokenAtSeq", 2);
    }

    @Test
    void theLogCannotBeChangedByTheApplicationOrOrdinaryAdministrators() {
        assertThatThrownBy(() -> applicationJdbc.sql("UPDATE audit_log SET action = 'X'").update()).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> applicationJdbc.sql("DELETE FROM audit_log").update()).hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> owner.sql("UPDATE audit_log SET action = 'X' WHERE tenant_id = :t").param("t", merchant.tenantId()).update())
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> owner.sql("DELETE FROM audit_log WHERE tenant_id = :t").param("t", merchant.tenantId()).update())
                .hasStackTraceContaining("append-only");
    }

    @Test
    void aRecordRollsBackWithTheActionItDescribes() {
        long before = count();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            tenantScope.apply(merchant.tenantId());
            auditLog.record(merchant.tenantId(), "TEST_ACTION", null, null, Map.of());
            throw new IllegalStateException("simulated failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count()).isEqualTo(before);
    }

    @Test
    void anotherBusinessSeesNoneOfTheLog() {
        TestMerchant other = TestMerchant.register(port);

        List<Map<String, Object>> theirs = entriesOf(other);

        assertThat(theirs).hasSize(2);
        assertThat(theirs.toString()).doesNotContain(merchant.tenantId().toString());
    }

    @Test
    void logsCanBeFilteredAndPaged() {
        for (int i = 0; i < 3; i++) {
            merchant.capturedPayment(1_000);
        }

        Map<String, Object> first = get("/api/v1/audit-log?action=PAYMENT_CAPTURE_REQUESTED&limit=2");
        Map<String, Object> second = get("/api/v1/audit-log?action=PAYMENT_CAPTURE_REQUESTED&limit=2&before=" + first.get("nextCursor"));

        assertThat((List<?>) first.get("items")).hasSize(2);
        assertThat((List<?>) second.get("items")).hasSize(1);
        assertThat(second.get("nextCursor")).isNull();
    }

    private void tamper(String sql) {
        ownerTx.executeWithoutResult(status -> {
            owner.sql("ALTER TABLE audit_log DISABLE TRIGGER audit_log_append_only").update();
            owner.sql(sql).param("t", merchant.tenantId()).update();
            owner.sql("ALTER TABLE audit_log ENABLE TRIGGER audit_log_append_only").update();
        });
    }

    private long count() {
        return owner.sql("SELECT count(*) FROM audit_log WHERE tenant_id = :t").param("t", merchant.tenantId()).query(Long.class).single();
    }

    private Map<String, Object> verify() {
        return get("/api/v1/audit-log/verify");
    }

    private List<Map<String, Object>> entries() {
        return entriesOf(merchant);
    }

    private List<Map<String, Object>> entriesOf(TestMerchant who) {
        Map<String, Object> page = who.client().get().uri("/api/v1/audit-log?limit=100")
                .header("Authorization", "Bearer " + who.token()).retrieve().body(Map.class);
        return (List<Map<String, Object>>) page.get("items");
    }

    private Map<String, Object> get(String uri) {
        return merchant.client().get().uri(uri).header("Authorization", "Bearer " + merchant.token()).retrieve().body(Map.class);
    }
}
