package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ReservationCoverageService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily account-wide Reserved Instance / Savings Plan coverage snapshot. Disabled by default —
 * the underlying Cost Explorer calls are both rate-limited and billed per call, same rationale
 * as {@code ActualCostIngestionScheduler}.
 */
@Component
@ConditionalOnProperty(name = "cost.reservations.enabled", havingValue = "true", matchIfMissing = false)
public class ReservationCoverageScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationCoverageScheduler.class);
    private static final String LOCK_NAME = "reservation_coverage_snapshot";

    private final ReservationCoverageService reservationCoverageService;
    private final ScheduledJobLockService lockService;

    @Value("${cost.reservations.enabled:false}")
    private boolean enabled;

    public ReservationCoverageScheduler(ReservationCoverageService reservationCoverageService,
                                         ScheduledJobLockService lockService) {
        this.reservationCoverageService = reservationCoverageService;
        this.lockService = lockService;
    }

    @Scheduled(cron = "${cost.reservations.cron:0 15 5 * * *}")
    public void scheduledReservationCoverageSnapshot() {
        if (!enabled) {
            return;
        }
        lockService.runLocked(LOCK_NAME, () -> {
            try {
                reservationCoverageService.captureDailySnapshot();
            } catch (Exception e) {
                log.error("Error during scheduled reservation coverage capture: {}", e.getMessage(), e);
            }
        });
    }
}
