package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.LockService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Warns holders of expiring locks and releases expired ones every locks.expiry.interval-ms under
 * the cluster job lock (E07-T02, E07-T03). Absent unless locks.expiry.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "locks.expiry.enabled", havingValue = "true")
public class LockExpiryScheduler {

    static final String JOB_NAME = "lock-expiry";

    private final LockService lockService;
    private final ScheduledJobLockService jobLockService;

    public LockExpiryScheduler(LockService lockService, ScheduledJobLockService jobLockService) {
        this.lockService = lockService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(fixedDelayString = "${locks.expiry.interval-ms:60000}", initialDelayString = "60000")
    public void run() {
        jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(5), () -> {
            lockService.processExpiringLockWarnings(); // warn first (E07-T03), then release
            lockService.processExpiredLocks();
        });
    }
}
