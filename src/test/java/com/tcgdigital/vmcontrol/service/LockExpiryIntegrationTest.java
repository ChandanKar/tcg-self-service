package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLock;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.scheduler.LockExpiryScheduler;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Locks end on their own (E07-T02, H4): a lock taken for a duration gets an expiry; the sweep
 * releases overdue active locks once, with EXPIRED history, an audit row and a notification;
 * "until manually released" locks are never touched. Against MySQL; not @Transactional.
 */
class LockExpiryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LockService lockService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationContext context;

    private User holder;
    private User colleague;

    @BeforeEach
    void setUp() {
        holder = newUser("lock-holder-" + UUID.randomUUID() + "@example.com", false, false);
        colleague = newUser("lock-colleague-" + UUID.randomUUID() + "@example.com", false, false);
    }

    private Environment environmentWithUsers() {
        Environment env = newEnvironment("Expiry");
        grant(holder, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        grant(colleague, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        return env;
    }

    private void overdue(EnvironmentLock lock) {
        jdbcTemplate.update("UPDATE environment_lock SET expires_at = ? WHERE lock_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(2))), lock.getLockId());
    }

    private boolean active(EnvironmentLock lock) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM environment_lock WHERE lock_id = ?", Boolean.class, lock.getLockId()));
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void aLockTakenForThirtyMinutesExpiresInThirtyMinutes() {
        EnvironmentLock lock = lockService.acquireLock(environmentWithUsers().getEnvironmentId(),
                holder.getUserId(), "deploy", 30);

        assertThat(lock.getExpiresAt().toInstant())
                .isCloseTo(Instant.now().plus(Duration.ofMinutes(30)), within(Duration.ofSeconds(30)));
        assertThat(lockService.acquireLock(environmentWithUsers().getEnvironmentId(), holder.getUserId(), "open", null)
                .getExpiresAt()).isNull();
    }

    @Test
    void theSweepReleasesOnlyOverdueLocksWithHistoryAuditAndNotification() {
        Environment expiring = environmentWithUsers();
        Environment manual = environmentWithUsers();
        Environment future = environmentWithUsers();
        EnvironmentLock overdueLock = lockService.acquireLock(expiring.getEnvironmentId(), holder.getUserId(), "short", 30);
        EnvironmentLock manualLock = lockService.acquireLock(manual.getEnvironmentId(), holder.getUserId(), "open-ended", null);
        EnvironmentLock futureLock = lockService.acquireLock(future.getEnvironmentId(), holder.getUserId(), "long", 120);
        overdue(overdueLock);

        assertThat(lockService.processExpiredLocks()).isGreaterThanOrEqualTo(1);

        assertThat(active(overdueLock)).isFalse();
        assertThat(active(manualLock)).isTrue();
        assertThat(active(futureLock)).isTrue();
        assertThat(count("SELECT COUNT(*) FROM lock_history WHERE lock_id = ? AND action = 'EXPIRED' AND performed_by_user_id = ?",
                overdueLock.getLockId(), holder.getUserId())).isEqualTo(1);
        awaitAsync(() -> {
            assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action_type = 'LOCK_EXPIRED' AND environment_id = ?",
                    expiring.getEnvironmentId())).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'LOCK_EXPIRED'",
                    colleague.getUserId())).isPositive();
        });
        // Unlocked: someone else can take it now.
        assertThat(lockService.acquireLock(expiring.getEnvironmentId(), colleague.getUserId(), "my turn", 15).getIsActive()).isTrue();
    }

    @Test
    void expiringIsIdempotent() {
        EnvironmentLock lock = lockService.acquireLock(environmentWithUsers().getEnvironmentId(), holder.getUserId(), "short", 5);
        overdue(lock);

        assertThat(lockService.expireLock(lock.getLockId())).isTrue();
        assertThat(lockService.expireLock(lock.getLockId())).isFalse();
        assertThat(count("SELECT COUNT(*) FROM lock_history WHERE lock_id = ? AND action = 'EXPIRED'", lock.getLockId())).isEqualTo(1);
    }

    @Test
    void aLockNotYetDueIsNotExpiredEvenWhenAsked() {
        EnvironmentLock lock = lockService.acquireLock(environmentWithUsers().getEnvironmentId(), holder.getUserId(), "long", 60);

        assertThat(lockService.expireLock(lock.getLockId())).isFalse();
        assertThat(active(lock)).isTrue();
    }

    @Test
    void theSchedulerIsAbsentWhileTheFeatureIsOff() {
        assertThat(context.getBeansOfType(LockExpiryScheduler.class)).isEmpty();
    }
}
