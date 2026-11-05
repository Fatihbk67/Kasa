package com.kasa.admin;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.kasa.common.BusinessCalendar;

/**
 * Money moving across every merchant, for the operator's dashboard. Read through database functions that return
 * totals only, since the operator has no tenant and row-level security would otherwise show nothing.
 */
@Service
public class PlatformActivityService {

    public record Day(LocalDate day, long captured, long refunded, long captureCount, long authorizationAttempts,
                      long authorizationsApproved, long newMerchants) {
    }

    public record TopMerchant(UUID id, String name, String status, long captured, long captureCount) {
    }

    public record Activity(int days, String currency, List<Day> daily, List<TopMerchant> topMerchants) {
    }

    /** Volume is shown in one currency, so different currencies are never added together. */
    static final String CURRENCY = "TRY";
    private static final int TOP_LIMIT = 5;

    private final JdbcClient jdbc;

    public PlatformActivityService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The last {@code days} local days, today included. */
    public Activity activity(int days) {
        LocalDate today = BusinessCalendar.today();
        LocalDate first = today.minusDays(days - 1L);
        List<Day> daily = jdbc.sql("SELECT * FROM platform_daily_activity(:first, :last, :currency)")
                .param("first", first)
                .param("last", today)
                .param("currency", CURRENCY)
                .query((rs, rowNum) -> new Day(rs.getObject("day", LocalDate.class), rs.getLong("captured"),
                        rs.getLong("refunded"), rs.getLong("capture_count"), rs.getLong("authorization_attempts"),
                        rs.getLong("authorizations_approved"), rs.getLong("new_merchants")))
                .list();
        OffsetDateTime from = BusinessCalendar.startOf(first);
        OffsetDateTime to = BusinessCalendar.startOf(today.plusDays(1));
        List<TopMerchant> top = jdbc.sql("SELECT * FROM platform_top_merchants(:from, :to, :currency, :limit)")
                .param("from", from)
                .param("to", to)
                .param("currency", CURRENCY)
                .param("limit", TOP_LIMIT)
                .query((rs, rowNum) -> new TopMerchant(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("status"), rs.getLong("captured"), rs.getLong("capture_count")))
                .list();
        return new Activity(days, CURRENCY, daily, top);
    }
}
