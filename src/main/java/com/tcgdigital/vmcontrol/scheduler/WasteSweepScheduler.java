package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.WasteSweepService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** The daily waste sweep (E22-T02), under the cluster job lock; off unless waste.sweep.enabled. */
@Component
@ConditionalOnProperty(name = "waste.sweep.enabled", havingValue = "true")
public class WasteSweepScheduler {

    static final String JOB_NAME = "waste_sweep";

    private final WasteSweepService sweepService;
    private final ScheduledJobLockService jobLockService;

    public WasteSweepScheduler(WasteSweepService sweepService, ScheduledJobLockService jobLockService) {
        this.sweepService = sweepService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(cron = "${waste.sweep.cron:0 0 4 * * *}", zone = "UTC")
    public void run() {
        jobLockService.runLocked(JOB_NAME, Duration.ofHours(1), sweepService::sweep);
    }
}
