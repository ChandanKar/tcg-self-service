package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.VmMetricsArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vm.metrics.archive.enabled", havingValue = "true", matchIfMissing = true)
public class VmMetricsArchiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(VmMetricsArchiveScheduler.class);
    private static final String LOCK_NAME = "vm_metrics_archive";

    private final VmMetricsArchiveService archiveService;
    private final ScheduledJobLockService lockService;

    @Value("${vm.metrics.archive.enabled:true}")
    private boolean enabled;

    public VmMetricsArchiveScheduler(VmMetricsArchiveService archiveService, ScheduledJobLockService lockService) {
        this.archiveService = archiveService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${vm.metrics.archive.cron:0 30 2 * * *}")
    public void scheduledMetricsArchive() {
        if (!enabled || !lockService.tryAcquire(LOCK_NAME)) return;
        try {
            archiveService.archiveOldRawSamples();
        } catch (Exception e) {
            log.error("Error during scheduled VM metrics archive: {}", e.getMessage(), e);
        } finally {
            lockService.release(LOCK_NAME);
        }
    }
}
