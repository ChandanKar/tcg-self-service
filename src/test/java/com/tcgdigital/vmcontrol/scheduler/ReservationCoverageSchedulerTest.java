package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.CommitmentRecommendationService;
import com.tcgdigital.vmcontrol.service.CostDayBoundary;
import com.tcgdigital.vmcontrol.service.ReservationCoverageService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Purchase recommendations are captured with the coverage snapshot on Mondays only (E27-T01). */
class ReservationCoverageSchedulerTest {

    private void run(String instant, CommitmentRecommendationService recommendations, ReservationCoverageService coverage) {
        ScheduledJobLockService lock = mock(ScheduledJobLockService.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        }).when(lock).runLocked(anyString(), any(Runnable.class));
        CostDayBoundary boundary = mock(CostDayBoundary.class);
        when(boundary.today()).thenReturn(LocalDate.ofInstant(Instant.parse(instant), ZoneOffset.UTC));
        ReservationCoverageScheduler scheduler = new ReservationCoverageScheduler(coverage, lock, recommendations, boundary);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        scheduler.scheduledReservationCoverageSnapshot();
    }

    @Test
    void mondayRunsCaptureTheRecommendationsAfterTheCoverage() {
        CommitmentRecommendationService recommendations = mock(CommitmentRecommendationService.class);
        ReservationCoverageService coverage = mock(ReservationCoverageService.class);

        run("2026-10-12T05:15:00Z", recommendations, coverage);

        var order = inOrder(coverage, recommendations);
        order.verify(coverage).captureDailySnapshot();
        order.verify(recommendations).capture();
    }

    @Test
    void otherDaysOnlyTakeTheCoverageSnapshot() {
        CommitmentRecommendationService recommendations = mock(CommitmentRecommendationService.class);
        ReservationCoverageService coverage = mock(ReservationCoverageService.class);

        run("2026-10-13T05:15:00Z", recommendations, coverage);

        verify(coverage).captureDailySnapshot();
        verifyNoInteractions(recommendations);
    }

    @Test
    void aFailedCoverageSnapshotStillCapturesTheRecommendations() {
        CommitmentRecommendationService recommendations = mock(CommitmentRecommendationService.class);
        ReservationCoverageService coverage = mock(ReservationCoverageService.class);
        doThrow(new IllegalStateException("boom")).when(coverage).captureDailySnapshot();

        run("2026-10-12T05:15:00Z", recommendations, coverage);

        verify(recommendations).capture();
    }
}
