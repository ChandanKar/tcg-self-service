package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository;
import com.tcgdigital.vmcontrol.service.LeaseExpiryProcessor;
import com.tcgdigital.vmcontrol.service.LeaseService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The lease sweep runs under its job lock, warns first, and one failing lease does not stop the rest (E15-T05). */
class LeaseSchedulerTest {

    @Test
    void sweepsUnderTheJobLockAndIsolatesFailures() {
        LeaseService leaseService = mock(LeaseService.class);
        LeaseExpiryProcessor processor = mock(LeaseExpiryProcessor.class);
        EnvironmentLeaseRepository leases = mock(EnvironmentLeaseRepository.class);
        ScheduledJobLockService jobLock = mock(ScheduledJobLockService.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return true;
        }).when(jobLock).runLocked(eq("lease-sweep"), any(Duration.class), any(Runnable.class));
        when(leases.findOverdueIds(any())).thenReturn(List.of("a", "b"));
        when(processor.expire("a")).thenThrow(new IllegalStateException("boom"));

        new LeaseScheduler(leaseService, processor, leases, jobLock).run();

        verify(jobLock).runLocked(eq("lease-sweep"), any(Duration.class), any(Runnable.class));
        verify(leaseService).processLeaseWarnings();
        verify(processor).expire("a");
        verify(processor).expire("b");
    }
}
