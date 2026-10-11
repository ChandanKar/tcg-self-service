package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ChargebackService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Freezes last month's chargeback and emails it to finance (E23-T07), under the cluster job lock;
 * off unless chargeback.enabled.
 */
@Component
@ConditionalOnProperty(name = "chargeback.enabled", havingValue = "true")
public class ChargebackScheduler {

    static final String JOB_NAME = "chargeback_statement";

    private final ChargebackService chargebackService;
    private final ScheduledJobLockService jobLockService;

    public ChargebackScheduler(ChargebackService chargebackService, ScheduledJobLockService jobLockService) {
        this.chargebackService = chargebackService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(cron = "${chargeback.cron:0 0 6 5 * *}", zone = "UTC")
    public void run() {
        jobLockService.runLocked(JOB_NAME, Duration.ofHours(1), chargebackService::finalizeAndSendPreviousMonth);
    }
}
