package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.QuotaService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Warns team members when a quota reaches its warning level or runs out (E25-T07); only while quotas are on. */
@Component
@ConditionalOnProperty(name = "quotas.enabled", havingValue = "true")
public class QuotaWarningScheduler {

    static final String JOB_NAME = "quota_warnings";

    private final QuotaService quotaService;
    private final ScheduledJobLockService jobLockService;

    public QuotaWarningScheduler(QuotaService quotaService, ScheduledJobLockService jobLockService) {
        this.quotaService = quotaService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(cron = "${quotas.warning-cron:0 5 * * * *}", zone = "UTC")
    public void run() {
        jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(30), quotaService::sendWarnings);
    }
}
