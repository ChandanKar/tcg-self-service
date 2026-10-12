package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ComputeOptimizerService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** The daily Compute Optimizer capture runs under its job lock, and not at all when turned off (E30-T04). */
class ComputeOptimizerSchedulerTest {

    private final ComputeOptimizerService service = mock(ComputeOptimizerService.class);
    private final ScheduledJobLockService lockService = mock(ScheduledJobLockService.class);
    private final ComputeOptimizerScheduler scheduler = new ComputeOptimizerScheduler(service, lockService);

    @Test
    void theCaptureRunsInsideItsLock() {
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return true;
        }).when(lockService).runLocked(eq(ComputeOptimizerScheduler.LOCK_NAME), any(Runnable.class));

        scheduler.scheduledCapture();

        verify(lockService).runLocked(eq("compute_optimizer_capture"), any(Runnable.class));
        verify(service).captureRecommendations();
    }

    @Test
    void turnedOffItDoesNothing() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.scheduledCapture();

        verifyNoInteractions(lockService);
        verify(service, never()).captureRecommendations();
    }
}
