package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.StateSyncService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Scheduler for periodic VM state synchronization.
 */
@Component
@ConditionalOnProperty(name = "vm.state.sync.enabled", havingValue = "true", matchIfMissing = true)
public class StateSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(StateSyncScheduler.class);

    private final StateSyncService stateSyncService;
    private final ScheduledJobLockService lockService;

    @Value("${vm.state.sync.enabled:true}")
    private boolean syncEnabled;

    public StateSyncScheduler(StateSyncService stateSyncService, ScheduledJobLockService lockService) {
        this.stateSyncService = stateSyncService;
        this.lockService = lockService;
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

        // One instance at a time, so drift is detected and audited once (M6).
        lockService.runLocked("vm_state_sync", Duration.ofMinutes(30), () -> {
            try {
                stateSyncService.syncAllVmStates();
            } catch (Exception e) {
                log.error("Error during scheduled VM state sync: {}", e.getMessage(), e);
            }
        });
    }
}

