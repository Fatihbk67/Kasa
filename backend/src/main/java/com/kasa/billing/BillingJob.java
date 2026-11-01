package com.kasa.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Safe on several instances: a period can be invoiced once, and an attempt is leased to one job at a time. */
@Component
class BillingJob {

    private static final Logger log = LoggerFactory.getLogger(BillingJob.class);

    private final BillingService billing;

    BillingJob(BillingService billing) {
        this.billing = billing;
    }

    @Scheduled(fixedDelayString = "${kasa.billing.job-interval}", initialDelayString = "${kasa.billing.job-interval}")
    void run() {
        int handled = billing.runDue();
        if (handled > 0) {
            log.info("Billing handled {} subscriptions and invoices", handled);
        }
    }
}
