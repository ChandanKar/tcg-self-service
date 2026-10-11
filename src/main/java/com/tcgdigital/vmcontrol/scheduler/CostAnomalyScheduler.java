package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.CostAnomalyService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Pulls cost anomalies from AWS twice a day (E21-T02), under the cluster job lock. Each run is a
 * billed Cost Explorer call per page, so it is off by default and on in the 'cost' profile
 * (cost.anomalies.enabled).
 */
@Component
@ConditionalOnProperty(name = "cost.anomalies.enabled", havingValue = "true")
public class CostAnomalyScheduler {

    static final String JOB_NAME = "cost_anomaly_ingest";

    private final CostAnomalyService anomalyService;
    private final ScheduledJobLockService jobLockService;

    public CostAnomalyScheduler(CostAnomalyService anomalyService, ScheduledJobLockService jobLockService) {
        this.anomalyService = anomalyService;
        this.jobLockService = jobLockService;
    }

    @Scheduled(cron = "${cost.anomalies.cron:0 0 */12 * * *}", zone = "UTC")
    public void run() {
        jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(10), anomalyService::ingest);
    }
}
