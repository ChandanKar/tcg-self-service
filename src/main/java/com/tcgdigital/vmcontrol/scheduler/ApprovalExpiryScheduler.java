package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ApprovalRequestService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Expires approval requests nobody decided in time (E25-T04); only while approvals are on. */
@Component
@ConditionalOnProperty(name = "approvals.enabled", havingValue = "true")
public class ApprovalExpiryScheduler {

    static final String JOB_NAME = "approval_expiry";

    private final ApprovalRequestService requestService;
    private final ScheduledJobLockService jobLockService;

    public ApprovalExpiryScheduler(ApprovalRequestService requestService, ScheduledJobLockService jobLockService) {
        this.requestService = requestService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(cron = "${approvals.expiry-cron:0 */10 * * * *}", zone = "UTC")
    public void run() {
        jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(9), requestService::expire);
    }
}
