package com.kasa.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class BillingMathTest {

    private static final OffsetDateTime JAN_31 = OffsetDateTime.parse("2027-01-31T12:00:00+03:00");

    @Test
    void aShortMonthDoesNotPullLaterPeriodsBack() {
        assertThat(day(BillingMath.periodEnd(JAN_31, "MONTH", 1))).isEqualTo("2027-02-28");
        assertThat(day(BillingMath.periodEnd(JAN_31, "MONTH", 2))).isEqualTo("2027-03-31");
        assertThat(day(BillingMath.periodEnd(JAN_31, "MONTH", 3))).isEqualTo("2027-04-30");
        assertThat(day(BillingMath.periodEnd(JAN_31, "MONTH", 4))).isEqualTo("2027-05-31");
    }

    @Test
    void aLeapDayFallsBackInOrdinaryYears() {
        OffsetDateTime leap = OffsetDateTime.parse("2028-02-29T12:00:00+03:00");

        assertThat(day(BillingMath.periodEnd(leap, "YEAR", 1))).isEqualTo("2029-02-28");
        assertThat(day(BillingMath.periodEnd(leap, "YEAR", 4))).isEqualTo("2032-02-29");
    }

    @Test
    void theShareFollowsTheTimeLeft() {
        OffsetDateTime start = OffsetDateTime.parse("2027-01-01T00:00:00Z");
        OffsetDateTime end = start.plusDays(30);

        assertThat(BillingMath.unusedShare(30_000, start, end, start)).isEqualTo(30_000);
        assertThat(BillingMath.unusedShare(30_000, start, end, start.plusDays(10))).isEqualTo(20_000);
        assertThat(BillingMath.unusedShare(30_000, start, end, end)).isZero();
        assertThat(BillingMath.unusedShare(30_000, start, end, end.plusDays(1))).isZero();
    }

    @Test
    void theShareRoundsHalfUp() {
        OffsetDateTime start = OffsetDateTime.parse("2027-01-01T00:00:00Z");
        OffsetDateTime end = start.plusSeconds(3);

        assertThat(BillingMath.unusedShare(100, start, end, start.plusSeconds(1))).isEqualTo(67);
        assertThat(BillingMath.unusedShare(100, start, end, start.plusSeconds(2))).isEqualTo(33);
    }

    @Test
    void aLargeAmountOverAYearDoesNotOverflow() {
        OffsetDateTime start = OffsetDateTime.parse("2027-01-01T00:00:00Z");
        OffsetDateTime end = start.plusYears(1);

        long half = BillingMath.unusedShare(1_000_000_000_000L, start, end, end.minusSeconds(end.toEpochSecond() - start.toEpochSecond() - 1));

        assertThat(half).isPositive().isLessThan(1_000_000_000_000L);
    }

    @Test
    void aYearlyPlanCountsAsATwelfthPerMonth() {
        assertThat(BillingMath.monthlyValue(120_000, "YEAR")).isEqualTo(10_000);
        assertThat(BillingMath.monthlyValue(10_000, "MONTH")).isEqualTo(10_000);
    }

    private static String day(OffsetDateTime value) {
        return value.toLocalDate().toString();
    }
}
