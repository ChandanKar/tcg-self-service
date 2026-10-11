package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.CommitmentRecommendationService;
import com.tcgdigital.vmcontrol.service.CostDayBoundary;
import com.tcgdigital.vmcontrol.service.ReservationCoverageService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;

/**
 * Daily account-wide Reserved Instance / Savings Plan coverage snapshot. Enabled by default;
 * read-only (E08-T10): four billed Cost Explorer calls a day, skipped without AWS credentials.
 * On Mondays it also captures AWS's Savings Plan / RI purchase recommendations (E27-T01, three
 * more billed calls a week).
 */
@Component
@ConditionalOnProperty(name = "cost.reservations.enabled", havingValue = "true", matchIfMissing = true)
public class ReservationCoverageScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationCoverageScheduler.class);
    private static final String LOCK_NAME = "reservation_coverage_snapshot";

    private final ReservationCoverageService reservationCoverageService;
    private final ScheduledJobLockService lockService;
    private final CommitmentRecommendationService recommendationService;
    private final CostDayBoundary dayBoundary;

    @Value("${cost.reservations.enabled:true}")
    private boolean enabled;

    public ReservationCoverageScheduler(ReservationCoverageService reservationCoverageService,
                                         ScheduledJobLockService lockService,
                                         CommitmentRecommendationService recommendationService,
                                         CostDayBoundary dayBoundary) {
        this.reservationCoverageService = reservationCoverageService;
        this.lockService = lockService;
        this.recommendationService = recommendationService;
        this.dayBoundary = dayBoundary;
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
            if (dayBoundary.today().getDayOfWeek() == DayOfWeek.MONDAY) {
                try {
                    recommendationService.capture();
                } catch (Exception e) {
                    log.error("Error during commitment recommendation capture: {}", e.getMessage(), e);
                }
            }
        });
    }
}
