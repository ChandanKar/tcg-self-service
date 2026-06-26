package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.VmMetricsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vm.metrics.sync.enabled", havingValue = "true", matchIfMissing = true)
public class VmMetricsScheduler {

    private static final Logger log = LoggerFactory.getLogger(VmMetricsScheduler.class);
    private static final String LOCK_NAME = "vm_metrics_sync";

    private final VmMetricsService metricsService;
    private final ScheduledJobLockService lockService;

    @Value("${vm.metrics.sync.enabled:true}")
    private boolean enabled;

    public VmMetricsScheduler(VmMetricsService metricsService, ScheduledJobLockService lockService) {
        this.metricsService = metricsService;
        this.lockService = lockService;
    }

    @Scheduled(fixedRateString = "${vm.metrics.sync.interval:300000}",
            initialDelayString = "${vm.metrics.sync.initial-delay:90000}")
    public void scheduledMetricsSync() {
        if (!enabled || !lockService.tryAcquire(LOCK_NAME)) return;
        try {
            metricsService.syncRunningVmMetrics();
        } catch (Exception e) {
            log.error("Error during scheduled VM metrics sync: {}", e.getMessage(), e);
        } finally {
            lockService.release(LOCK_NAME);
        }
    }
}
