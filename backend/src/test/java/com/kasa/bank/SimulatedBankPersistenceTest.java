package com.kasa.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.kasa.reconciliation.ReconciliationService;
import com.kasa.support.Concurrency;
import com.kasa.support.PostgresIntegrationTest;
import com.kasa.support.TestMerchant;

/** A new instance over the same tables is what the bank looks like after our application restarts. */
class SimulatedBankPersistenceTest extends PostgresIntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    SimulatedBank bank;

    @Autowired
    ReconciliationService reconciliation;

    private SimulatedBank restarted() {
        return new SimulatedBank(jdbc, tx);
    }

    @Test
    void aRestartedBankStillKnowsItsAuthorizations() {
        BankResponse authorized = bank.authorize(UUID.randomUUID(), "tok_success", 1_000, "TRY");

        BankResponse captured = restarted().capture(UUID.randomUUID(), authorized.reference(), 1_000);

        assertThat(captured.approved()).isTrue();
    }

    @Test
    void aRestartedBankKeepsTheCardsBehaviour() {
        BankResponse authorized = bank.authorize(UUID.randomUUID(), "tok_capture_declined", 1_000, "TRY");

        BankResponse captured = restarted().capture(UUID.randomUUID(), authorized.reference(), 1_000);

        assertThat(captured.approved()).isFalse();
        assertThat(captured.declineCode()).isEqualTo("capture_rejected");
    }

    @Test
    void aRestartedBankStillAnswersLookupsAndStatements() {
        UUID authorization = UUID.randomUUID();
        BankResponse authorized = bank.authorize(authorization, "tok_success", 1_000, "TRY");
        BankResponse captured = bank.capture(UUID.randomUUID(), authorized.reference(), 1_000);

        assertThat(restarted().lookup(authorization)).contains(authorized);
        assertThat(restarted().downloadStatement(LocalDate.now(ZoneOffset.UTC))).contains(captured.reference());
    }

    @Test
    void anUnknownOperationIsStillUnknown() {
        assertThat(restarted().lookup(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aDeclineIsRememberedToo() {
        UUID operation = UUID.randomUUID();
        bank.authorize(operation, "tok_declined", 1_000, "TRY");

        assertThat(restarted().lookup(operation)).contains(BankResponse.declined("card_declined"));
    }

    @Test
    void repeatingAnOperationAfterARestartMovesNoMoreMoney() {
        BankResponse authorized = bank.authorize(UUID.randomUUID(), "tok_success", 1_000, "TRY");
        UUID capture = UUID.randomUUID();
        BankResponse first = bank.capture(capture, authorized.reference(), 1_000);

        BankResponse repeated = restarted().capture(capture, authorized.reference(), 1_000);

        assertThat(repeated).isEqualTo(first);
        assertThat(linesWith(first.reference())).isEqualTo(1);
    }

    @Test
    void manyCallersWithTheSameOperationMoveTheMoneyOnce() throws Exception {
        BankResponse authorized = bank.authorize(UUID.randomUUID(), "tok_success", 1_000, "TRY");
        UUID capture = UUID.randomUUID();

        List<BankResponse> responses = Concurrency.run(16, index -> () -> bank.capture(capture, authorized.reference(), 1_000));

        assertThat(responses.stream().map(BankResponse::reference).distinct()).hasSize(1);
        assertThat(linesWith(responses.getFirst().reference())).isEqualTo(1);
    }

    @Test
    void aPaymentIsCapturedAndReconciledAgainstTheStoredStatement() {
        TestMerchant merchant = TestMerchant.register(port);
        UUID payment = merchant.authorized(5_000, "tok_success");

        Map<String, Object> captured = merchant.post("/api/v1/payments/" + payment + "/capture", null);

        assertThat(((Map<String, Object>) captured.get("payment"))).containsEntry("status", "CAPTURED");
        ReconciliationService.RunDetail run = reconciliation.reconcile(LocalDate.now(ZoneOffset.UTC));
        assertThat(run.discrepancies()).noneMatch(found -> merchant.tenantId().equals(found.tenantId()));
    }

    private long linesWith(String reference) {
        return owner.sql("SELECT COUNT(*) FROM sim_bank_statement_lines WHERE reference = :reference")
                .param("reference", reference)
                .query(Long.class)
                .single();
    }
}
