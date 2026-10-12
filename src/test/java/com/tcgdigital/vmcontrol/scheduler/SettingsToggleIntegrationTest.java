package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.StateSyncService;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.*;

/**
 * Turning state sync off in Settings skips the next scheduled run, and resetting it brings the
 * run back, with no restart (E29-T03). The scheduler (absent in the test context, where state
 * sync is off) is built here around the real SettingsService and database.
 */
class SettingsToggleIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SettingsService settings;

    @AfterEach
    void restore() {
        settings.reset("vm.state.sync.enabled", null);
    }

    @Test
    void stateSyncFollowsTheSettingAtTheNextRun() {
        ScheduledJobLockService lock = Mockito.mock(ScheduledJobLockService.class);
        StateSyncScheduler scheduler = new StateSyncScheduler(Mockito.mock(StateSyncService.class), lock);
        ReflectionTestUtils.setField(scheduler, "syncEnabled", true);
        ReflectionTestUtils.setField(scheduler, "settings", settings);

        settings.set("vm.state.sync.enabled", "false", null);
        scheduler.scheduledStateSync();
        Mockito.verify(lock, Mockito.never()).runLocked(anyString(), any(Duration.class), any(Runnable.class));

        settings.reset("vm.state.sync.enabled", null);
        scheduler.scheduledStateSync();
        Mockito.verify(lock).runLocked(eq("vm_state_sync"), any(Duration.class), any(Runnable.class));
    }
}
