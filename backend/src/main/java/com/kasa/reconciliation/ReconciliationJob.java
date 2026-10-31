package com.kasa.reconciliation;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class ReconciliationJob {

    private final ReconciliationService reconciliation;

    ReconciliationJob(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "${kasa.reconciliation.cron}", zone = "UTC")
    void reconcileYesterday() {
        reconciliation.reconcileIfMissing(LocalDate.now(ZoneOffset.UTC).minusDays(1));
    }
}
