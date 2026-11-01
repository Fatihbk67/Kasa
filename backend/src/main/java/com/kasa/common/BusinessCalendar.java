package com.kasa.common;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Merchants think in local days, so day-based reports and filters use this zone rather than UTC. */
public final class BusinessCalendar {

    public static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");

    private BusinessCalendar() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static OffsetDateTime startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toOffsetDateTime();
    }
}
