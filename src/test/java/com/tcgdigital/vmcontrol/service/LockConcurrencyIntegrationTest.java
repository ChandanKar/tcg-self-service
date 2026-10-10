package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.LockAlreadyHeldException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLock;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * One active lock per environment, enforced by the database (E07-T01, M1): simultaneous acquires
 * end with exactly one lock and a 409 for the loser; a failing notification never undoes a lock.
 * Against MySQL; not @Transactional (each acquire commits as in production).
 */
class LockConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LockService lockService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private NotificationService notificationService;

    private Environment env;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Race");
        alice = newUser("alice-" + UUID.randomUUID() + "@example.com", false, false);
        bob = newUser("bob-" + UUID.randomUUID() + "@example.com", false, false);
        grant(alice, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        grant(bob, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
    }

    private int activeLocks() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM environment_lock WHERE environment_id = ? AND is_active = TRUE",
                Integer.class, env.getEnvironmentId());
        return n == null ? 0 : n;
    }

    /** Both users acquire at the same moment, many times over: never two active locks. */
    @Test
    void simultaneousAcquiresLeaveExactlyOneLock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Object>> results = new ArrayList<>();
                for (User user : List.of(alice, bob)) {
                    results.add(pool.submit(() -> {
                        go.await();
                        try {
                            return lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "race", null);
                        } catch (LockAlreadyHeldException e) {
                            return e;
                        }
                    }));
                }
                go.countDown();
                List<Object> outcomes = new ArrayList<>();
                for (Future<Object> f : results) {
                    outcomes.add(f.get(30, TimeUnit.SECONDS));
                }

                assertThat(outcomes).filteredOn(o -> o instanceof EnvironmentLock).hasSize(1);
                assertThat(outcomes).filteredOn(o -> o instanceof LockAlreadyHeldException).singleElement()
                        .satisfies(o -> {
                            LockAlreadyHeldException e = (LockAlreadyHeldException) o;
                            EnvironmentLock winner = (EnvironmentLock) outcomes.stream()
                                    .filter(x -> x instanceof EnvironmentLock).findFirst().orElseThrow();
                            assertThat(e.getLockedByUserId()).isEqualTo(winner.getLockedByUserId());
                        });
                assertThat(activeLocks()).isEqualTo(1);

                EnvironmentLock winner = (EnvironmentLock) outcomes.stream()
                        .filter(x -> x instanceof EnvironmentLock).findFirst().orElseThrow();
                lockService.releaseLock(env.getEnvironmentId(), winner.getLockedByUserId());
                assertThat(activeLocks()).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theSameUserAcquiringTwiceGetsTheSameLock() {
        EnvironmentLock first = lockService.acquireLock(env.getEnvironmentId(), alice.getUserId(), "mine", null);
        EnvironmentLock second = lockService.acquireLock(env.getEnvironmentId(), alice.getUserId(), "mine again", null);

        assertThat(second.getLockId()).isEqualTo(first.getLockId());
        assertThat(activeLocks()).isEqualTo(1);
    }

    @Test
    void aFailingNotificationDoesNotUndoTheLock() {
        doThrow(new IllegalStateException("mail server down"))
                .when(notificationService).notifyLockAcquiredForEnvironment(anyString(), anyString(), anyString(), any());

        EnvironmentLock lock = lockService.acquireLock(env.getEnvironmentId(), alice.getUserId(), "despite mail", null);

        assertThat(lock.getIsActive()).isTrue();
        assertThat(activeLocks()).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesASecondActiveLock() {
        lockService.acquireLock(env.getEnvironmentId(), alice.getUserId(), "first", null);

        // Bypassing the service entirely: the unique index still holds.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO environment_lock (lock_id, environment_id, locked_by_user_id, is_active) VALUES (?, ?, ?, TRUE)",
                        UUID.randomUUID().toString(), env.getEnvironmentId(), bob.getUserId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(activeLocks()).isEqualTo(1);
    }
}
