package com.kasa.dispute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Safe to run on several instances at once: resolving a dispute is a no-op once it has an outcome. */
@Component
class DisputeJob {

    private static final Logger log = LoggerFactory.getLogger(DisputeJob.class);

    private final DisputeService disputes;
    private final DisputeProperties properties;

    DisputeJob(DisputeService disputes, DisputeProperties properties) {
        this.disputes = disputes;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${kasa.disputes.job-interval}", initialDelayString = "${kasa.disputes.job-interval}")
    void run() {
        int resolved = disputes.resolveDue(properties.reviewTime());
        if (resolved > 0) {
            log.info("Resolved {} disputes", resolved);
        }
    }
}
