package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ChargebackService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** The monthly chargeback job (E23-T07) runs the finalize-and-send under its job lock. */
class ChargebackSchedulerTest {

    @Test
    void runsUnderTheJobLock() {
        ChargebackService service = mock(ChargebackService.class);
        ScheduledJobLockService locks = mock(ScheduledJobLockService.class);

        new ChargebackScheduler(service, locks).run();

        ArgumentCaptor<Runnable> job = ArgumentCaptor.forClass(Runnable.class);
        verify(locks).runLocked(eq("chargeback_statement"), eq(Duration.ofHours(1)), job.capture());
        job.getValue().run();
        verify(service).finalizeAndSendPreviousMonth();
    }
}
