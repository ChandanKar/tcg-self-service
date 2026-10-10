package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Holders are warned before their lock expires and can extend it (E07-T03): the extension counts
 * from the current expiry, is capped by locks.max-duration-minutes, is holder-only and leaves an
 * EXTENDED history row; each warning threshold warns once, and an extension re-arms them.
 * Against MySQL; not @Transactional.
 */
class LockExtendIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LockService lockService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User holder;
    private User colleague;

    @BeforeEach
    void setUp() {
        holder = newUser("extend-holder-" + UUID.randomUUID() + "@example.com", false, false);
        colleague = newUser("extend-colleague-" + UUID.randomUUID() + "@example.com", false, false);
    }

    private Environment environmentWithUsers() {
        Environment env = newEnvironment("Extend");
        grant(holder, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        grant(colleague, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        return env;
    }

    private Instant expiresAt(EnvironmentLock lock) {
        return jdbcTemplate.queryForObject("SELECT expires_at FROM environment_lock WHERE lock_id = ?",
                Timestamp.class, lock.getLockId()).toInstant();
    }

    /** Moves the lock so it was taken {@code agoMinutes} ago and expires in {@code inMinutes}. */
    private void shift(EnvironmentLock lock, long agoMinutes, long inMinutes) {
        Instant now = Instant.now();
        jdbcTemplate.update("UPDATE environment_lock SET locked_at = ?, expires_at = ? WHERE lock_id = ?",
                Timestamp.from(now.minus(Duration.ofMinutes(agoMinutes))),
                Timestamp.from(now.plus(Duration.ofMinutes(inMinutes))), lock.getLockId());
    }

    private int warnings(Environment env) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ? "
                + "AND type = 'LOCK_EXPIRING' AND entity_id = ?", Integer.class, holder.getUserId(), env.getEnvironmentId());
        return n == null ? 0 : n;
    }

    /** Simulates time passing: the warnings sent so far were sent {@code minutes} earlier. */
    private void ageWarnings(Environment env, long minutes) {
        jdbcTemplate.update("UPDATE notification SET created_at = DATE_SUB(created_at, INTERVAL ? MINUTE) "
                + "WHERE user_id = ? AND type = 'LOCK_EXPIRING' AND entity_id = ?", minutes, holder.getUserId(), env.getEnvironmentId());
    }

    @Test
    void extendingAddsMinutesToTheCurrentExpiryAndRecordsHistory() {
        Environment env = environmentWithUsers();
        EnvironmentLock lock = lockService.acquireLock(env.getEnvironmentId(), holder.getUserId(), "deploy", 30);
        Instant before = expiresAt(lock);

        EnvironmentLock extended = lockService.extend(env.getEnvironmentId(), holder.getUserId(), 60);

        assertThat(extended.getExpiresAt().toInstant()).isCloseTo(before.plus(Duration.ofMinutes(60)), within(Duration.ofSeconds(2)));
        assertThat(expiresAt(lock)).isCloseTo(before.plus(Duration.ofMinutes(60)), within(Duration.ofSeconds(2)));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lock_history WHERE lock_id = ? AND action = 'EXTENDED' "
                + "AND performed_by_user_id = ?", Integer.class, lock.getLockId(), holder.getUserId())).isEqualTo(1);
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action_type = 'LOCK_EXTENDED' "
                + "AND environment_id = ?", Integer.class, env.getEnvironmentId())).isEqualTo(1));
    }

    @Test
    void anAlreadyOverdueLockIsExtendedFromNow() {
        Environment env = environmentWithUsers();
        EnvironmentLock lock = lockService.acquireLock(env.getEnvironmentId(), holder.getUserId(), "deploy", 30);
        shift(lock, 40, -10);

        EnvironmentLock extended = lockService.extend(env.getEnvironmentId(), holder.getUserId(), 15);

        assertThat(extended.getExpiresAt().toInstant()).isCloseTo(Instant.now().plus(Duration.ofMinutes(15)), within(Duration.ofSeconds(30)));
    }

    @Test
    void anExtensionPastTheMaximumDurationIsRefused() {
        Environment env = environmentWithUsers();
        EnvironmentLock lock = lockService.acquireLock(env.getEnvironmentId(), holder.getUserId(), "deploy", 60);
        shift(lock, 1300, 60); // 1360 minutes so far; +120 would make 1480 > 1440

        assertThatThrownBy(() -> lockService.extend(env.getEnvironmentId(), holder.getUserId(), 120))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("1440");
        assertThat(lockService.extend(env.getEnvironmentId(), holder.getUserId(), 60).getExpiresAt()).isNotNull();
    }

    @Test
    void onlyTheHolderCanExtendAndOnlyALockWithAnExpiry() {
        Environment env = environmentWithUsers();
        lockService.acquireLock(env.getEnvironmentId(), holder.getUserId(), "deploy", 30);

        assertThatThrownBy(() -> lockService.extend(env.getEnvironmentId(), colleague.getUserId(), 30))
                .isInstanceOf(UnauthorizedException.class);

        Environment open = environmentWithUsers();
        lockService.acquireLock(open.getEnvironmentId(), holder.getUserId(), "open-ended", null);
        assertThatThrownBy(() -> lockService.extend(open.getEnvironmentId(), holder.getUserId(), 30))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no expiry");
    }

    @Test
    void eachThresholdWarnsOnceAndAnExtensionReArmsTheWarnings() {
        Environment env = environmentWithUsers();
        EnvironmentLock lock = lockService.acquireLock(env.getEnvironmentId(), holder.getUserId(), "deploy", 60);

        shift(lock, 50, 10); // inside the 15-minute window only
        lockService.processExpiringLockWarnings();
        lockService.processExpiringLockWarnings();
        assertThat(warnings(env)).isEqualTo(1);

        ageWarnings(env, 7);
        shift(lock, 57, 3); // seven minutes on: inside the 5-minute window as well
        lockService.processExpiringLockWarnings();
        lockService.processExpiringLockWarnings();
        assertThat(warnings(env)).isEqualTo(2);

        lockService.extend(env.getEnvironmentId(), holder.getUserId(), 15); // expires in ~18 minutes
        lockService.processExpiringLockWarnings();
        assertThat(warnings(env)).isEqualTo(2);

        // Ten minutes later the re-armed 15-minute warning fires again.
        ageWarnings(env, 10);
        jdbcTemplate.update("UPDATE environment_lock SET expires_at = ? WHERE lock_id = ?",
                Timestamp.from(Instant.now().plus(Duration.ofMinutes(8))), lock.getLockId());
        lockService.processExpiringLockWarnings();
        assertThat(warnings(env)).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT message FROM notification WHERE user_id = ? AND type = 'LOCK_EXPIRING' "
                + "AND entity_id = ? ORDER BY created_at DESC LIMIT 1", String.class, holder.getUserId(), env.getEnvironmentId()))
                .contains("Extend it from the environment page");
    }

    @Test
    void aShortLockInsideBothWindowsIsWarnedOnce() {
        Environment env = environmentWithUsers();
        lockService.acquireLock(env.getEnvironmentId(), holder.getUserId(), "quick", 5);

        lockService.processExpiringLockWarnings();

        assertThat(warnings(env)).isEqualTo(1);
    }

    @Test
    void openEndedAndFarOffLocksAreNotWarned() {
        Environment open = environmentWithUsers();
        Environment far = environmentWithUsers();
        lockService.acquireLock(open.getEnvironmentId(), holder.getUserId(), "open-ended", null);
        lockService.acquireLock(far.getEnvironmentId(), holder.getUserId(), "long", 120);

        lockService.processExpiringLockWarnings();

        assertThat(warnings(open)).isZero();
        assertThat(warnings(far)).isZero();
    }
}
