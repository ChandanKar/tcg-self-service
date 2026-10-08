package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.config.SchedulingConfig;
import com.tcgdigital.vmcontrol.service.EksSyncService;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import com.tcgdigital.vmcontrol.service.StateSyncService;
import com.tcgdigital.vmcontrol.service.VmDiscoveryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.FileInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every scheduler runs under the cluster-wide job lock, on a pool of scheduler threads
 * (E06-T01, M6, H26).
 */
class SchedulerLockingTest {

    // ---------------------------------------------------------------- the scheduler pool (H26)

    /** The pool settings as shipped in src/main/resources/application.properties. */
    private static String[] shippedPoolProperties() throws Exception {
        Properties props = new Properties();
        try (InputStream in = new FileInputStream("src/main/resources/application.properties")) {
            props.load(in);
        }
        String size = props.getProperty("spring.task.scheduling.pool.size").replaceAll("\\$\\{[^:]+:(\\d+)}", "$1");
        return new String[] {
                "spring.task.scheduling.pool.size=" + size,
                "spring.task.scheduling.thread-name-prefix=" + props.getProperty("spring.task.scheduling.thread-name-prefix")
        };
    }

    @Test
    void schedulingUsesAPoolOfSixThreads() throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingConfig.class)
                .withPropertyValues(shippedPoolProperties())
                .run(context -> {
                    ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler) context.getBean(TaskScheduler.class);
                    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(6);
                    assertThat(scheduler.getThreadNamePrefix()).isEqualTo("sched-");
                });
    }

    /** One job blocks for a while; another must keep ticking on a different thread meanwhile. */
    @Configuration
    static class TwoJobs {
        static final CountDownLatch slowStarted = new CountDownLatch(1);
        static final AtomicInteger fastTicksDuringSlow = new AtomicInteger();
        static volatile boolean slowRunning;

        @Bean
        Object jobs() {
            return new Object() {
                @Scheduled(fixedRate = 60_000)
                public void slow() throws InterruptedException {
                    slowRunning = true;
                    slowStarted.countDown();
                    Thread.sleep(1_500);
                    slowRunning = false;
                }

                @Scheduled(fixedRate = 100)
                public void fast() {
                    if (slowRunning) fastTicksDuringSlow.incrementAndGet();
                }
            };
        }
    }

    @Test
    void aLongJobDoesNotMakeAnotherMissItsTicks() throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                .withUserConfiguration(SchedulingConfig.class, TwoJobs.class)
                .withPropertyValues(shippedPoolProperties())
                .run(context -> {
                    assertThat(TwoJobs.slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    Thread.sleep(1_200);
                    assertThat(TwoJobs.fastTicksDuringSlow.get()).isGreaterThan(5);
                });
    }

    // ---------------------------------------------------------------- the newly locked schedulers (M6)

    /** A lock service that runs the job (lock free) and remembers the name and TTL it was given. */
    private static ScheduledJobLockService freeLock() {
        ScheduledJobLockService lock = mock(ScheduledJobLockService.class);
        when(lock.runLocked(any(), any(Duration.class), any())).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return true;
        });
        return lock;
    }

    private static ScheduledJobLockService heldElsewhere() {
        ScheduledJobLockService lock = mock(ScheduledJobLockService.class);
        when(lock.runLocked(any(), any(Duration.class), any())).thenReturn(false);
        return lock;
    }

    @Test
    void stateSyncRunsUnderItsLock() {
        StateSyncService sync = mock(StateSyncService.class);
        ScheduledJobLockService lock = freeLock();
        StateSyncScheduler scheduler = new StateSyncScheduler(sync, lock);
        ReflectionTestUtils.setField(scheduler, "syncEnabled", true);

        scheduler.scheduledStateSync();

        verify(lock).runLocked(eq("vm_state_sync"), eq(Duration.ofMinutes(30)), any());
        verify(sync).syncAllVmStates();
    }

    @Test
    void stateSyncDoesNothingWhenAnotherInstanceHoldsTheLock() {
        StateSyncService sync = mock(StateSyncService.class);
        StateSyncScheduler scheduler = new StateSyncScheduler(sync, heldElsewhere());
        ReflectionTestUtils.setField(scheduler, "syncEnabled", true);

        scheduler.scheduledStateSync();

        verify(sync, never()).syncAllVmStates();
    }

    @Test
    void discoveryRunsUnderItsLock() throws Exception {
        VmDiscoveryService discovery = mock(VmDiscoveryService.class);
        ScheduledJobLockService lock = freeLock();

        new VmDiscoveryScheduler(discovery, lock).scheduledDiscovery();

        verify(lock).runLocked(eq("vm_discovery"), eq(Duration.ofMinutes(30)), any());
        verify(discovery).discoverAndRegisterVms();
    }

    @Test
    void eksSyncRunsUnderItsLock() {
        EksSyncService eks = mock(EksSyncService.class);
        ScheduledJobLockService lock = freeLock();
        EksSyncScheduler scheduler = new EksSyncScheduler(eks, lock);
        ReflectionTestUtils.setField(scheduler, "statusRefreshEnabled", true);

        scheduler.scheduledEksSync();

        verify(lock).runLocked(eq("eks_sync"), eq(Duration.ofMinutes(30)), any());
        verify(eks).syncRegisteredEksEnvironments();
    }

    @Test
    void eksSyncWithBothSwitchesOffDoesNotTakeTheLock() {
        EksSyncService eks = mock(EksSyncService.class);
        ScheduledJobLockService lock = freeLock();

        new EksSyncScheduler(eks, lock).scheduledEksSync();

        verifyNoInteractions(lock, eks);
    }

    @Test
    void accessExpirationRunsUnderItsLock() {
        EnvironmentAccessService access = mock(EnvironmentAccessService.class);
        ScheduledJobLockService lock = freeLock();

        new AccessExpirationScheduler(access, lock).processExpiredAccess();

        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(lock).runLocked(name.capture(), eq(Duration.ofMinutes(30)), any());
        assertThat(name.getValue()).isEqualTo("access_expiration");
        verify(access).processExpiredAccess();
        verify(access).processExpiringAccessWarnings();
    }

    @Test
    void manualAccessExpirationStaysUnlocked() {
        EnvironmentAccessService access = mock(EnvironmentAccessService.class);
        ScheduledJobLockService lock = freeLock();

        new AccessExpirationScheduler(access, lock).triggerManualExpiration();

        verifyNoInteractions(lock);
        verify(access).processExpiredAccess();
    }
}
