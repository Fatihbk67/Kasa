package com.kasa.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Safe to run on several instances at once: applying an outcome is a no-op once the operation is resolved. */
@Component
class PaymentRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryJob.class);

    private final PaymentService payments;
    private final PaymentProperties properties;

    PaymentRecoveryJob(PaymentService payments, PaymentProperties properties) {
        this.payments = payments;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${kasa.payments.recovery-interval}",
            initialDelayString = "${kasa.payments.recovery-interval}")
    void run() {
        int resolved = payments.recoverStale(properties.recoveryStaleAfter());
        if (resolved > 0) {
            log.info("Recovered {} payment operations", resolved);
        }
    }
}
