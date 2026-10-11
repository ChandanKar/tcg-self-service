package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.EksSplitCostIngestionService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Ingests EKS pod/namespace cost from Split Cost Allocation Data once a day (E26-T04), under the
 * cluster job lock; absent unless eks.scad.enabled. Runs after the 05:00 actual-cost ingestion.
 */
@Component
@ConditionalOnProperty(name = "eks.scad.enabled", havingValue = "true")
public class EksSplitCostScheduler {

    static final String JOB_NAME = "eks_split_cost";

    private final EksSplitCostIngestionService ingestionService;
    private final ScheduledJobLockService jobLockService;

    public EksSplitCostScheduler(EksSplitCostIngestionService ingestionService, ScheduledJobLockService jobLockService) {
        this.ingestionService = ingestionService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(cron = "${eks.scad.cron:0 30 6 * * *}", zone = "UTC")
    public void run() {
        jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(30), ingestionService::ingest);
    }
}
