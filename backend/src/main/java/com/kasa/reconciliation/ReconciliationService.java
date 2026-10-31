package com.kasa.reconciliation;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.kasa.bank.BankGateway;

/**
 * Compares a day's bank statement with our records and reports every difference. It records and alerts but
 * never corrects anything: a difference in money needs a person to decide which side is right.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    public record RunView(UUID id, LocalDate statementDate, String status, int bankLineCount, int matchedCount,
                          int discrepancyCount, String error, OffsetDateTime createdAt) {
    }

    public record DiscrepancyView(String kind, String bankReference, UUID tenantId, UUID sourceId, Long expectedAmount,
                                  Long actualAmount, String detail) {
    }

    public record RunDetail(RunView run, List<DiscrepancyView> discrepancies) {
    }

    private record Movement(UUID sourceId, UUID tenantId, String type, long amount, String currency, String reference) {
    }

    private static final int LIST_LIMIT = 100;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final BankGateway bank;

    public ReconciliationService(JdbcClient jdbc, TransactionTemplate tx, BankGateway bank) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.bank = bank;
    }

    /** For the daily job: does nothing if the date already has a completed run. */
    public void reconcileIfMissing(LocalDate date) {
        boolean completed = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM reconciliation_runs
                                       WHERE statement_date = :date AND status = 'COMPLETED')
                        """)
                .param("date", date)
                .query(Boolean.class)
                .single();
        if (!completed) {
            reconcile(date);
        }
    }

    /** Each run is kept, so re-running a date adds to the history instead of overwriting what was found before. */
    public RunDetail reconcile(LocalDate date) {
        UUID runId = UUID.randomUUID();
        List<BankStatementParser.Line> lines;
        Set<String> nearbyReferences = new HashSet<>();
        try {
            lines = BankStatementParser.parse(bank.downloadStatement(date));
            for (LocalDate day : List.of(date.minusDays(1), date.plusDays(1))) {
                BankStatementParser.parse(bank.downloadStatement(day))
                        .forEach(line -> nearbyReferences.add(line.reference()));
            }
        } catch (BankStatementParser.MalformedStatementException e) {
            log.error("Bank statement for {} was rejected: {}", date, e.getMessage());
            insertRun(runId, date, "FAILED", 0, 0, 0, e.getMessage());
            return get(runId);
        }

        List<DiscrepancyView> discrepancies = new ArrayList<>();
        int matched = compareBankLines(lines, discrepancies);
        findMissingInBank(date, lines, nearbyReferences, discrepancies);
        findLedgerDrift(discrepancies);

        tx.executeWithoutResult(status -> {
            insertRun(runId, date, "COMPLETED", lines.size(), matched, discrepancies.size(), null);
            discrepancies.forEach(discrepancy -> insertDiscrepancy(runId, discrepancy));
        });
        if (!discrepancies.isEmpty()) {
            log.warn("Reconciliation for {} found {} discrepancies", date, discrepancies.size());
        }
        return get(runId);
    }

    public List<RunView> listRuns() {
        return jdbc.sql("SELECT * FROM reconciliation_runs ORDER BY statement_date DESC, created_at DESC LIMIT :limit")
                .param("limit", LIST_LIMIT)
                .query(ReconciliationService::mapRun)
                .list();
    }

    public RunDetail get(UUID runId) {
        RunView run = jdbc.sql("SELECT * FROM reconciliation_runs WHERE id = :id")
                .param("id", runId)
                .query(ReconciliationService::mapRun)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "reconciliation run not found"));
        List<DiscrepancyView> discrepancies = jdbc.sql("SELECT * FROM reconciliation_discrepancies WHERE run_id = :id ORDER BY kind, id")
                .param("id", runId)
                .query((rs, rowNum) -> new DiscrepancyView(
                        rs.getString("kind"),
                        rs.getString("bank_reference"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("source_id", UUID.class),
                        rs.getObject("expected_amount", Long.class),
                        rs.getObject("actual_amount", Long.class),
                        rs.getString("detail")))
                .list();
        return new RunDetail(run, discrepancies);
    }

    private int compareBankLines(List<BankStatementParser.Line> lines, List<DiscrepancyView> discrepancies) {
        Map<String, Movement> ours = findByReferences(lines.stream().map(BankStatementParser.Line::reference).toList());
        Set<String> seen = new HashSet<>();
        int matched = 0;
        for (BankStatementParser.Line line : lines) {
            Movement movement = ours.get(line.reference());
            if (!seen.add(line.reference())) {
                discrepancies.add(new DiscrepancyView("DUPLICATE_IN_BANK", line.reference(), tenantOf(movement),
                        sourceOf(movement), null, line.amount(), "reference appears more than once in the statement"));
            } else if (movement == null) {
                discrepancies.add(new DiscrepancyView("MISSING_IN_KASA", line.reference(), null, null, null,
                        line.amount(), "bank booked a " + line.type() + " we have no record of"));
            } else if (movement.amount() != line.amount()) {
                discrepancies.add(new DiscrepancyView("AMOUNT_MISMATCH", line.reference(), movement.tenantId(),
                        movement.sourceId(), movement.amount(), line.amount(), null));
            } else if (!movement.type().equals(line.type()) || !movement.currency().equals(line.currency())) {
                discrepancies.add(new DiscrepancyView("DETAILS_MISMATCH", line.reference(), movement.tenantId(),
                        movement.sourceId(), movement.amount(), line.amount(), "ours: " + movement.type() + " "
                        + movement.currency() + ", bank: " + line.type() + " " + line.currency()));
            } else {
                matched++;
            }
        }
        return matched;
    }

    /** A movement near midnight can be booked on the neighbouring day, which is timing and not an error. */
    private void findMissingInBank(LocalDate date, List<BankStatementParser.Line> lines, Set<String> nearbyReferences,
                                   List<DiscrepancyView> discrepancies) {
        Set<String> bankReferences = new HashSet<>(nearbyReferences);
        lines.forEach(line -> bankReferences.add(line.reference()));
        OffsetDateTime from = date.atStartOfDay().atOffset(ZoneOffset.UTC);
        List<Movement> movements = jdbc.sql("""
                        SELECT source_id, tenant_id, type, amount, currency, bank_reference
                        FROM find_bank_movements_booked_between(:from, :to)
                        """)
                .param("from", from)
                .param("to", from.plusDays(1))
                .query(ReconciliationService::mapMovement)
                .list();
        for (Movement movement : movements) {
            if (!bankReferences.contains(movement.reference())) {
                discrepancies.add(new DiscrepancyView("MISSING_IN_BANK", movement.reference(), movement.tenantId(),
                        movement.sourceId(), movement.amount(), null, "we recorded a " + movement.type()
                        + " the bank did not book"));
            }
        }
    }

    private void findLedgerDrift(List<DiscrepancyView> discrepancies) {
        jdbc.sql("SELECT id, tenant_id, code, balance, computed FROM find_all_drifted_ledger_accounts()")
                .query((rs, rowNum) -> new DiscrepancyView("LEDGER_DRIFT", null, rs.getObject("tenant_id", UUID.class),
                        rs.getObject("id", UUID.class), rs.getLong("computed"), rs.getLong("balance"),
                        "stored balance of " + rs.getString("code") + " differs from its postings"))
                .list()
                .forEach(discrepancies::add);
    }

    private Map<String, Movement> findByReferences(List<String> references) {
        Map<String, Movement> movements = new HashMap<>();
        if (references.isEmpty()) {
            return movements;
        }
        // Looks up by reference across all time: an operation recovered days late is still a match.
        jdbc.sql("""
                        SELECT source_id, tenant_id, type, amount, currency, bank_reference
                        FROM find_bank_movements_by_references(string_to_array(:references, ','))
                        """)
                .param("references", String.join(",", references))
                .query(ReconciliationService::mapMovement)
                .list()
                .forEach(movement -> movements.put(movement.reference(), movement));
        return movements;
    }

    private void insertRun(UUID id, LocalDate date, String status, int lineCount, int matched, int discrepancies,
                           String error) {
        jdbc.sql("""
                        INSERT INTO reconciliation_runs (id, statement_date, status, bank_line_count, matched_count,
                                                         discrepancy_count, error)
                        VALUES (:id, :date, :status, :lineCount, :matched, :discrepancies, :error)
                        """)
                .param("id", id)
                .param("date", date)
                .param("status", status)
                .param("lineCount", lineCount)
                .param("matched", matched)
                .param("discrepancies", discrepancies)
                .param("error", error)
                .update();
    }

    private void insertDiscrepancy(UUID runId, DiscrepancyView discrepancy) {
        jdbc.sql("""
                        INSERT INTO reconciliation_discrepancies (run_id, kind, bank_reference, tenant_id, source_id,
                                                                  expected_amount, actual_amount, detail)
                        VALUES (:runId, :kind, :reference, :tenantId, :sourceId, :expected, :actual, :detail)
                        """)
                .param("runId", runId)
                .param("kind", discrepancy.kind())
                .param("reference", discrepancy.bankReference())
                .param("tenantId", discrepancy.tenantId())
                .param("sourceId", discrepancy.sourceId())
                .param("expected", discrepancy.expectedAmount())
                .param("actual", discrepancy.actualAmount())
                .param("detail", discrepancy.detail())
                .update();
    }

    private static UUID tenantOf(Movement movement) {
        return movement == null ? null : movement.tenantId();
    }

    private static UUID sourceOf(Movement movement) {
        return movement == null ? null : movement.sourceId();
    }

    private static Movement mapMovement(ResultSet rs, int rowNum) throws SQLException {
        return new Movement(
                rs.getObject("source_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("type"),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getString("bank_reference"));
    }

    private static RunView mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new RunView(
                rs.getObject("id", UUID.class),
                rs.getObject("statement_date", LocalDate.class),
                rs.getString("status"),
                rs.getInt("bank_line_count"),
                rs.getInt("matched_count"),
                rs.getInt("discrepancy_count"),
                rs.getString("error"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
