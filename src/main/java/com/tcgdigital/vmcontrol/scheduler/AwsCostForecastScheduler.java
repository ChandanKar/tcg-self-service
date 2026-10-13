package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.AwsCostForecastService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Captures AWS's cost forecast weekly (E35-T01): Monday 06:00 UTC, two billed, read-only Cost
 * Explorer calls; skipped without AWS credentials or while cost.aws-forecast.enabled is off.
 */
@Component
@ConditionalOnProperty(name = "cost.aws-forecast.enabled", havingValue = "true", matchIfMissing = true)
public class AwsCostForecastScheduler {

    private static final Logger log = LoggerFactory.getLogger(AwsCostForecastScheduler.class);
    static final String LOCK_NAME = "aws_cost_forecast";

    private final AwsCostForecastService forecastService;
    private final ScheduledJobLockService lockService;

    public AwsCostForecastScheduler(AwsCostForecastService forecastService, ScheduledJobLockService lockService) {
        this.forecastService = forecastService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${cost.aws-forecast.cron:0 0 6 * * MON}", zone = "UTC")
    public void scheduledCapture() {
        if (!forecastService.isEnabled()) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                forecastService.capture();
            } catch (Exception e) {
                log.error("Error during the AWS cost forecast capture: {}", e.getMessage(), e);
            }
        });
    }
}
