package com.kasa.billing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;

import com.kasa.common.BusinessCalendar;

/** The date and money arithmetic of billing, kept pure so every edge case can be tested without a database. */
final class BillingMath {

    private BillingMath() {
    }

    /**
     * Always counts from the anchor rather than the previous end. Otherwise one short month would pull a
     * subscription started on the 31st back to the 28th for the rest of its life.
     */
    static OffsetDateTime periodEnd(OffsetDateTime anchor, String interval, int periodIndex) {
        var local = anchor.atZoneSameInstant(BusinessCalendar.ZONE);
        var end = interval.equals("YEAR") ? local.plusYears(periodIndex) : local.plusMonths(periodIndex);
        return end.toOffsetDateTime();
    }

    /** The part of {@code amount} that belongs to the time left, in BigInteger because amount times seconds can overflow a long. */
    static long unusedShare(long amount, OffsetDateTime start, OffsetDateTime end, OffsetDateTime now) {
        long total = Duration.between(start, end).toSeconds();
        long left = Duration.between(now, end).toSeconds();
        if (left <= 0 || total <= 0) {
            return 0;
        }
        if (left >= total) {
            return amount;
        }
        return new BigDecimal(BigInteger.valueOf(amount).multiply(BigInteger.valueOf(left)))
                .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Yearly plans count as a twelfth per month, rounded half up. */
    static long monthlyValue(long amount, String interval) {
        return interval.equals("YEAR") ? (amount + 6) / 12 : amount;
    }
}
