package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.EksSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for periodic EKS cluster and node group synchronisation.
 * Disabled by default — enable with eks.sync.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "eks.sync.enabled", havingValue = "true", matchIfMissing = false)
public class EksSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(EksSyncScheduler.class);

    private final EksSyncService eksSyncService;

    public EksSyncScheduler(EksSyncService eksSyncService) {
        this.eksSyncService = eksSyncService;
    }

    @Scheduled(fixedRateString = "${eks.sync.interval:300000}",
               initialDelayString = "${eks.sync.initial-delay:60000}")
    public void scheduledEksSync() {
        log.debug("Scheduled EKS sync triggered");
        try {
            int synced = eksSyncService.syncAllEksClusters();
            log.debug("EKS sync completed — {} node groups processed", synced);
        } catch (Exception e) {
            log.error("Error during scheduled EKS sync: {}", e.getMessage(), e);
        }
    }
}
