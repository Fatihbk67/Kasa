package com.kasa.analytics;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.kasa.common.BusinessCalendar;
import com.kasa.tenant.TenantScope;

@Service
public class AnalyticsService {

    /** {@code disputed} is what disputes took in the period after reversals, and can be negative after a good month. */
    public record Totals(long captured, long refunded, long fees, long disputed, long disputeFees, long net, long captureCount,
                         long authorizationAttempts, long authorizationsApproved) {
    }

    private record DisputeMoves(long disputed, long fees) {
    }

    public record DayPoint(LocalDate day, long captured, long refunded, long captureCount) {
    }

    public record StatusCount(String status, long count, long amount) {
    }

    public record FailureCount(String code, long count) {
    }

    public record Summary(int days, String currency, Totals totals, List<DayPoint> daily, List<StatusCount> statuses,
                          List<FailureCount> failures) {
    }

    private record DayRow(DayPoint point, long fees) {
    }

    private final JdbcClient jdbc;
    private final TenantScope tenantScope;

    public AnalyticsService(JdbcClient jdbc, TenantScope tenantScope) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
    }

    /** Covers the last {@code days} local days, today included. Money is counted on the day it actually moved. */
    @Transactional(readOnly = true)
    public Summary summary(UUID tenantId, int days, String currency) {
        tenantScope.apply(tenantId);
        LocalDate today = BusinessCalendar.today();
        LocalDate first = today.minusDays(days - 1L);
        OffsetDateTime from = BusinessCalendar.startOf(first);
        OffsetDateTime to = BusinessCalendar.startOf(today.plusDays(1));

        List<DayRow> rows = dailyRows(first, today, from, to, currency);
        List<DayPoint> daily = rows.stream().map(DayRow::point).toList();
        long captured = daily.stream().mapToLong(DayPoint::captured).sum();
        long refunded = daily.stream().mapToLong(DayPoint::refunded).sum();
        long fees = rows.stream().mapToLong(DayRow::fees).sum();
        long captureCount = daily.stream().mapToLong(DayPoint::captureCount).sum();

        List<StatusCount> statuses = statuses(from, to, currency);
        List<FailureCount> failures = failures(from, to, currency);
        long declined = failures.stream().mapToLong(FailureCount::count).sum();
        long approved = approvedAuthorizations(from, to, currency);

        DisputeMoves disputes = disputeMoves(from, to, currency);
        Totals totals = new Totals(captured, refunded, fees, disputes.disputed(), disputes.fees(),
                captured - fees - refunded - disputes.disputed() - disputes.fees(), captureCount,
                approved + declined, approved);
        return new Summary(days, currency, totals, daily, statuses, failures);
    }

    private DisputeMoves disputeMoves(OffsetDateTime from, OffsetDateTime to, String currency) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(o.amount) FILTER (WHERE o.type = 'DISPUTE_WITHDRAWAL'), 0)
                                   - COALESCE(SUM(o.amount) FILTER (WHERE o.type = 'DISPUTE_REVERSAL'), 0) AS disputed,
                               COALESCE(SUM(o.amount) FILTER (WHERE o.type = 'DISPUTE_FEE'), 0) AS fees
                        FROM payment_operations o
                        JOIN payments p ON p.id = o.payment_id
                        WHERE o.status = 'SUCCEEDED' AND o.type IN ('DISPUTE_WITHDRAWAL', 'DISPUTE_REVERSAL', 'DISPUTE_FEE')
                          AND p.currency = :currency AND o.updated_at >= :from AND o.updated_at < :to
                        """)
                .param("currency", currency)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new DisputeMoves(rs.getLong("disputed"), rs.getLong("fees")))
                .single();
    }

    private List<DayRow> dailyRows(LocalDate first, LocalDate last, OffsetDateTime from, OffsetDateTime to, String currency) {
        return jdbc.sql("""
                        WITH days AS (
                            SELECT generate_series(:first::date, :last::date, interval '1 day')::date AS day
                        ), moves AS (
                            SELECT (o.updated_at AT TIME ZONE 'Europe/Istanbul')::date AS day, o.type, o.amount, p.fee_amount
                            FROM payment_operations o
                            JOIN payments p ON p.id = o.payment_id
                            WHERE o.status = 'SUCCEEDED' AND o.type IN ('CAPTURE', 'REFUND') AND p.currency = :currency
                              AND o.updated_at >= :from AND o.updated_at < :to
                        )
                        SELECT d.day,
                               COALESCE(SUM(m.amount) FILTER (WHERE m.type = 'CAPTURE'), 0) AS captured,
                               COALESCE(SUM(m.amount) FILTER (WHERE m.type = 'REFUND'), 0) AS refunded,
                               COALESCE(SUM(m.fee_amount) FILTER (WHERE m.type = 'CAPTURE'), 0) AS fees,
                               COUNT(*) FILTER (WHERE m.type = 'CAPTURE') AS capture_count
                        FROM days d
                        LEFT JOIN moves m ON m.day = d.day
                        GROUP BY d.day
                        ORDER BY d.day
                        """)
                .param("first", first)
                .param("last", last)
                .param("currency", currency)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new DayRow(new DayPoint(
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("captured"),
                        rs.getLong("refunded"),
                        rs.getLong("capture_count")), rs.getLong("fees")))
                .list();
    }

    private List<StatusCount> statuses(OffsetDateTime from, OffsetDateTime to, String currency) {
        return jdbc.sql("""
                        SELECT status, COUNT(*) AS count, COALESCE(SUM(amount), 0) AS amount
                        FROM payments
                        WHERE currency = :currency AND created_at >= :from AND created_at < :to
                        GROUP BY status
                        ORDER BY count DESC, status
                        """)
                .param("currency", currency)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new StatusCount(rs.getString("status"), rs.getLong("count"), rs.getLong("amount")))
                .list();
    }

    private List<FailureCount> failures(OffsetDateTime from, OffsetDateTime to, String currency) {
        return jdbc.sql("""
                        SELECT o.failure_code, COUNT(*) AS count
                        FROM payment_operations o
                        JOIN payments p ON p.id = o.payment_id
                        WHERE o.type = 'AUTHORIZE' AND o.status = 'FAILED' AND p.currency = :currency
                          AND o.created_at >= :from AND o.created_at < :to
                        GROUP BY o.failure_code
                        ORDER BY count DESC, o.failure_code
                        """)
                .param("currency", currency)
                .param("from", from)
                .param("to", to)
                .query((rs, rowNum) -> new FailureCount(rs.getString("failure_code"), rs.getLong("count")))
                .list();
    }

    private long approvedAuthorizations(OffsetDateTime from, OffsetDateTime to, String currency) {
        return jdbc.sql("""
                        SELECT COUNT(*)
                        FROM payment_operations o
                        JOIN payments p ON p.id = o.payment_id
                        WHERE o.type = 'AUTHORIZE' AND o.status = 'SUCCEEDED' AND p.currency = :currency
                          AND o.created_at >= :from AND o.created_at < :to
                        """)
                .param("currency", currency)
                .param("from", from)
                .param("to", to)
                .query(Long.class)
                .single();
    }
}
