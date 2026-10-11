package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLease;
import com.tcgdigital.vmcontrol.model.EnvironmentLeaseParticipant;
import com.tcgdigital.vmcontrol.model.EnvironmentLock;
import com.tcgdigital.vmcontrol.model.LeaseSource;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseParticipantRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Leases follow locks with an expiry, and end a user's part when their lock ends or their access
 * does (E15-T03). Not @Transactional: the hooks run after commit.
 */
@TestPropertySource(properties = "lease.enabled=true")
class LeaseLockIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LockService lockService;
    @Autowired private LeaseService leaseService;
    @Autowired private EnvironmentAccessService accessService;
    @Autowired private EnvironmentLeaseRepository leases;
    @Autowired private EnvironmentLeaseParticipantRepository participants;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User alice;
    private User bob;
    private User admin;
    private Environment env;

    @BeforeEach
    void setUp() {
        alice = newUser("alice-lock-lease@example.org", false, false);
        bob = newUser("bob-lock-lease@example.org", false, false);
        admin = newUser("admin-lock-lease@example.org", true, false);
        env = newEnvironment("Lock lease");
        env.setLeaseEnabled(true);
        env.setLeaseMaxMinutes(720);
        env = environmentRepository.saveAndFlush(env);
        grant(alice, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        grant(bob, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
    }

    private EnvironmentLease activeLease() {
        return leases.findActiveByEnvironmentId(env.getEnvironmentId()).orElseThrow();
    }

    private List<String> activeParticipants() {
        return participants.findByLeaseIdAndLeftAtIsNullOrderByJoinedAtAsc(activeLease().getLeaseId()).stream()
                .map(EnvironmentLeaseParticipant::getUserId).toList();
    }

    private static void assertNear(Timestamp actual, Duration fromNow) {
        assertThat(actual.toInstant()).isCloseTo(Instant.now().plus(fromNow), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void aLockWithAnExpiryOpensALeaseEndingWithIt() {
        EnvironmentLock lock = lockService.acquireLock(env.getEnvironmentId(), alice.getUserId(), "release testing", 120);

        EnvironmentLease lease = activeLease();
        assertThat(lease.getSource()).isEqualTo(LeaseSource.LOCK);
        assertThat(lease.getLockId()).isEqualTo(lock.getLockId());
        assertNear(lease.getEndsAt(), Duration.ofMinutes(120));
        assertThat(activeParticipants()).containsExactly(alice.getUserId());
    }

    @Test
    void aLockWithoutAnExpiryOpensNoLease() {
        lockService.acquireLock(env.getEnvironmentId(), alice.getUserId(), "open-ended", null);
        assertThat(leases.findActiveByEnvironmentId(env.getEnvironmentId())).isEmpty();
    }

    @Test
    void extendingTheLockMovesTheLeaseEnd() {
        lockService.acquireLock(env.getEnvironmentId(), alice.getUserId(), "release testing", 60);
        lockService.extend(env.getEnvironmentId(), alice.getUserId(), 60);
        assertNear(activeLease().getEndsAt(), Duration.ofMinutes(120));
    }

    @Test
    void releasingTheLockLeavesAThirtyMinuteGrace() {
        lockService.acquireLock(env.getEnvironmentId(), alice.getUserId(), "release testing", 120);
        lockService.releaseLock(env.getEnvironmentId(), alice.getUserId());

        EnvironmentLease lease = activeLease();
        assertThat(lease.getLockId()).isNull();
        assertNear(lease.getEndsAt(), Duration.ofMinutes(30));
        assertThat(activeParticipants()).isEmpty();
    }

    @Test
    void breakingOrExpiringTheLockAlsoStartsTheGrace() {
        lockService.acquireLock(env.getEnvironmentId(), alice.getUserId(), "release testing", 120);
        lockService.breakLock(env.getEnvironmentId(), admin.getUserId(), "needed for a hotfix");
        assertNear(activeLease().getEndsAt(), Duration.ofMinutes(30));

        // A new lock follows the same lease; letting it expire starts the grace again.
        EnvironmentLock second = lockService.acquireLock(env.getEnvironmentId(), bob.getUserId(), "again", 300);
        assertNear(activeLease().getEndsAt(), Duration.ofMinutes(300));
        jdbcTemplate.update("UPDATE environment_lock SET expires_at = ? WHERE lock_id = ?",
                Timestamp.from(Instant.now().minusSeconds(5)), second.getLockId());
        assertThat(lockService.expireLock(second.getLockId())).isTrue();
        assertNear(activeLease().getEndsAt(), Duration.ofMinutes(30));
    }

    @Test
    void whenTheLastParticipantLosesAccessTheLeaseEndsWithinFifteenMinutes() {
        leaseService.openOrJoinOnStart(env.getEnvironmentId(), bob.getUserId(), 240);
        accessService.revokeAccess(env.getEnvironmentId(), bob.getUserId(), admin.getUserId());

        assertNear(activeLease().getEndsAt(), Duration.ofMinutes(15));
        assertThat(activeParticipants()).isEmpty();
    }

    @Test
    void whenOthersRemainTheEndDoesNotMove() {
        leaseService.openOrJoinOnStart(env.getEnvironmentId(), alice.getUserId(), 240);
        leaseService.openOrJoinOnStart(env.getEnvironmentId(), bob.getUserId(), 240);
        accessService.revokeAccess(env.getEnvironmentId(), bob.getUserId(), admin.getUserId());

        assertNear(activeLease().getEndsAt(), Duration.ofMinutes(240));
        assertThat(activeParticipants()).containsExactly(alice.getUserId());
    }
}
