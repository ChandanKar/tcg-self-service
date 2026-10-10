package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.LockService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The lock-expiry sweep runs under the cluster job lock, so two instances never sweep at once
 * (E07-T02).
 */
class LockExpirySchedulerTest {

    @Test
    void theSweepRunsThroughRunLockedAsLockExpiry() {
        LockService lockService = mock(LockService.class);
        ScheduledJobLockService jobLocks = mock(ScheduledJobLockService.class);

        new LockExpiryScheduler(lockService, jobLocks).run();

        ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        verify(jobLocks).runLocked(eq("lock-expiry"), eq(Duration.ofMinutes(5)), job.capture());
        job.getValue().run();
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(lockService);
        order.verify(lockService).processExpiringLockWarnings(); // E07-T03: warn before releasing
        order.verify(lockService).processExpiredLocks();
    }
}
