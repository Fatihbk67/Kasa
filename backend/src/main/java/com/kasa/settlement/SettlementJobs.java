package com.kasa.settlement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Safe on several instances: a daily uniqueness rule and an advisory lock stop a merchant being paid twice. */
@Component
class SettlementJobs {

    private static final Logger log = LoggerFactory.getLogger(SettlementJobs.class);

    private final SettlementService settlement;
    private final SettlementProperties properties;

    SettlementJobs(SettlementService settlement, SettlementProperties properties) {
        this.settlement = settlement;
        this.properties = properties;
    }

    @Scheduled(cron = "${kasa.settlement.cron}", zone = "UTC")
    void settleDaily() {
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        int created = settlement.settleAll(startOfToday.minus(properties.delay()));
        log.info("Daily settlement created {} payouts", created);
    }

    @Scheduled(fixedDelayString = "${kasa.settlement.recovery-interval}",
            initialDelayString = "${kasa.settlement.recovery-interval}")
    void recoverPayouts() {
        int resolved = settlement.recoverStale(properties.recoveryStaleAfter());
        if (resolved > 0) {
            log.info("Recovered {} payouts", resolved);
        }
    }
}
