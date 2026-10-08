package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.ScheduledJobLock;
import com.tcgdigital.vmcontrol.repository.ScheduledJobLockRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * runLocked: one instance runs a scheduled job; a failing job still releases its lock; another
 * instance's lock is never released (E06-T01, M6). Against MySQL; not @Transactional.
 */
class ScheduledJobLockServiceTest extends AbstractIntegrationTest {

    @Autowired private ScheduledJobLockService lockService;
    @Autowired private ScheduledJobLockRepository locks;

    private ScheduledJobLock row(String name) {
        return locks.findById(name).orElseThrow();
    }

    @Test
    void runsWhenFreeHoldsTheLockWhileRunningAndReleasesIt() {
        AtomicReference<ScheduledJobLock> during = new AtomicReference<>();

        boolean ran = lockService.runLocked("test_job_free", Duration.ofMinutes(10),
                () -> during.set(locks.findById("test_job_free").orElseThrow()));

        assertThat(ran).isTrue();
        assertThat(during.get().getLockedBy()).isEqualTo(lockService.getOwnerId());
        assertThat(Duration.between(Instant.now(), during.get().getLockedUntil().toInstant()))
                .isBetween(Duration.ofMinutes(9), Duration.ofMinutes(11));
        assertThat(row("test_job_free").getLockedUntil().toInstant()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void doesNotRunOrReleaseWhenAnotherInstanceHoldsTheLock() {
        ScheduledJobLock other = new ScheduledJobLock();
        other.setLockName("test_job_held");
        other.setLockedBy("other-host-4242");
        other.setAcquiredAt(Timestamp.from(Instant.now()));
        // Whole seconds: the column rounds fractions, which made this comparison flaky.
        Timestamp until = Timestamp.from(Instant.now().plus(Duration.ofMinutes(20)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        other.setLockedUntil(until);
        locks.saveAndFlush(other);
        AtomicInteger runs = new AtomicInteger();

        boolean ran = lockService.runLocked("test_job_held", runs::incrementAndGet);

        assertThat(ran).isFalse();
        assertThat(runs).hasValue(0);
        assertThat(row("test_job_held").getLockedBy()).isEqualTo("other-host-4242");
        assertThat(row("test_job_held").getLockedUntil().toInstant().getEpochSecond())
                .isEqualTo(until.toInstant().getEpochSecond());
    }

    @Test
    void aFailingJobReleasesItsLockSoTheNextTickRuns() {
        boolean first = lockService.runLocked("test_job_fails", () -> {
            throw new IllegalStateException("boom");
        });
        AtomicInteger runs = new AtomicInteger();
        boolean second = lockService.runLocked("test_job_fails", runs::incrementAndGet);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    void anExpiredLockOfAnotherInstanceIsTakenOver() {
        ScheduledJobLock stale = new ScheduledJobLock();
        stale.setLockName("test_job_stale");
        stale.setLockedBy("crashed-host-1");
        stale.setAcquiredAt(Timestamp.from(Instant.now().minus(Duration.ofHours(2))));
        stale.setLockedUntil(Timestamp.from(Instant.now().minus(Duration.ofHours(1))));
        locks.saveAndFlush(stale);
        AtomicInteger runs = new AtomicInteger();

        assertThat(lockService.runLocked("test_job_stale", runs::incrementAndGet)).isTrue();
        assertThat(runs).hasValue(1);
    }
}
