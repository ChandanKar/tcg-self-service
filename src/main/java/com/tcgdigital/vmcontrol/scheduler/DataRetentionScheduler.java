package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.DataRetentionService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Nightly data retention (E11-T07) under the cluster job lock, so only one instance prunes.
 * Absent unless retention.enabled=true: enable it once the compliance owner confirms the periods.
 */
@Component
@ConditionalOnProperty(name = "retention.enabled", havingValue = "true")
public class DataRetentionScheduler {

    static final String JOB_NAME = "data_retention";

    private final DataRetentionService retentionService;
    private final ScheduledJobLockService jobLockService;

    public DataRetentionScheduler(DataRetentionService retentionService, ScheduledJobLockService jobLockService) {
        this.retentionService = retentionService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(cron = "${retention.cron:0 0 3 * * *}")
    public void run() {
        jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(30), retentionService::purgeAll);
    }
}
