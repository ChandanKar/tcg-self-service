package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.SpotPriceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily Spot price capture (E31-T02); skipped while spot.pricing.enabled is off. Read-only. */
@Component
public class SpotPriceScheduler {

    private static final Logger log = LoggerFactory.getLogger(SpotPriceScheduler.class);
    static final String LOCK_NAME = "spot_price_capture";

    private final SpotPriceService spotPriceService;
    private final ScheduledJobLockService lockService;

    public SpotPriceScheduler(SpotPriceService spotPriceService, ScheduledJobLockService lockService) {
        this.spotPriceService = spotPriceService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${spot.pricing.cron:0 20 4 * * *}")
    public void scheduledCapture() {
        if (!spotPriceService.isEnabled()) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                spotPriceService.captureDaily();
            } catch (Exception e) {
                log.error("Error during scheduled Spot price capture: {}", e.getMessage(), e);
            }
        });
    }
}
