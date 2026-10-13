package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.OperationRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fails operations with no progress for vm.operations.stale-after-minutes every few minutes, so a
 * run whose process died does not show "in progress" until someone starts another operation on
 * that environment. Each run is failed by a conditional status change, so several instances may
 * sweep at once without a job lock.
 */
@Component
public class OperationStaleSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(OperationStaleSweepScheduler.class);

    private final OperationRecoveryService recoveryService;

    @Value("${vm.operations.recovery.enabled:true}")
    private boolean enabled = true;

    public OperationStaleSweepScheduler(OperationRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(fixedDelayString = "${vm.operations.stale-sweep-ms:300000}", initialDelayString = "${vm.operations.stale-sweep-ms:300000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            int failed = recoveryService.failStale(null);
            if (failed > 0) {
                log.warn("Failed {} operation(s) with no progress for too long", failed);
            }
        } catch (Exception e) {
            log.error("Stale operation sweep failed: {}", e.getMessage(), e);
        }
    }
}
