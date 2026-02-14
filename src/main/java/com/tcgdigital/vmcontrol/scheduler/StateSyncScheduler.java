package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.StateSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for periodic VM state synchronization.
 */
@Component
@ConditionalOnProperty(name = "vm.state.sync.enabled", havingValue = "true", matchIfMissing = true)
public class StateSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(StateSyncScheduler.class);

    private final StateSyncService stateSyncService;

    @Value("${vm.state.sync.enabled:true}")
    private boolean syncEnabled;

    public StateSyncScheduler(StateSyncService stateSyncService) {
        this.stateSyncService = stateSyncService;
    }

    /**
     * Scheduled task to sync VM states.
     * Default: every 5 minutes (300000 ms).
     */
    @Scheduled(fixedRateString = "${vm.state.sync.interval:300000}", initialDelayString = "${vm.state.sync.initial-delay:60000}")
    public void scheduledStateSync() {
        if (!syncEnabled) {
            log.debug("VM state sync is disabled, skipping");
            return;
        }

        log.debug("Scheduled VM state sync triggered");

        try {
            stateSyncService.syncAllVmStates();
        } catch (Exception e) {
            log.error("Error during scheduled VM state sync: {}", e.getMessage(), e);
        }
    }
}

