package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.SpotFallbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Spot fallback check every 5 minutes (E31-T06); skipped while spot.fallback.enabled is off. */
@Component
public class SpotFallbackScheduler {

    private static final Logger log = LoggerFactory.getLogger(SpotFallbackScheduler.class);
    static final String LOCK_NAME = "spot_fallback";

    private final SpotFallbackService fallbackService;
    private final ScheduledJobLockService lockService;

    public SpotFallbackScheduler(SpotFallbackService fallbackService, ScheduledJobLockService lockService) {
        this.fallbackService = fallbackService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${spot.fallback.cron:0 */5 * * * *}")
    public void scheduledEvaluation() {
        if (!fallbackService.isEnabled()) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                fallbackService.evaluate();
            } catch (Exception e) {
                log.error("Error during the Spot fallback check: {}", e.getMessage(), e);
            }
        });
    }
}
