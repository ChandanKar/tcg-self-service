package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.VmMetricRollupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cloudwatch.metric.rollup.enable", havingValue = "true", matchIfMissing = true)
public class VmMetricRollupScheduler {

    private static final Logger log = LoggerFactory.getLogger(VmMetricRollupScheduler.class);
    private static final String LOCK_NAME = "vm_metric_daily_rollup";

    private final VmMetricRollupService rollupService;
    private final ScheduledJobLockService lockService;

    @Value("${cloudwatch.metric.rollup.enable:true}")
    private boolean enabled;

    public VmMetricRollupScheduler(VmMetricRollupService rollupService, ScheduledJobLockService lockService) {
        this.rollupService = rollupService;
        this.lockService = lockService;
    }

    @Scheduled(fixedRateString = "#{${cloudwatch.metric.rollup.schedule.interval:60} * 60000}",
            initialDelayString = "#{${cloudwatch.metric.rollup.schedule.initial-delay:3} * 60000}")
    public void scheduledRollup() {
        if (!enabled) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                rollupService.rollupDailyMetrics();
            } catch (Exception e) {
                log.error("Error during scheduled VM metric daily rollup: {}", e.getMessage(), e);
            }
        });
    }
}
