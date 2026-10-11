package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLease;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lease warnings at 60/15/5 minutes (E15-T04): each threshold once per person, re-armed by an
 * extension; recipients are participants, the lock holder and recent operators who can still
 * operate. Time passing is simulated by moving ends_at and back-dating warnings.
 */
@TestPropertySource(properties = "lease.enabled=true")
class LeaseWarningIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LeaseService leaseService;
    @Autowired private LockService lockService;
    @Autowired private EnvironmentLeaseRepository leases;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User alice;
    private Environment env;

    @BeforeEach
    void setUp() {
        alice = newUser("alice-warn@example.org", false, false);
        env = newEnvironment("Warn lease");
        env.setLeaseEnabled(true);
        env = environmentRepository.saveAndFlush(env);
        grant(alice, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
    }

    private EnvironmentLease leaseEndingIn(Duration remaining) {
        leaseService.openOrJoinOnStart(env.getEnvironmentId(), alice.getUserId(), 240);
        EnvironmentLease lease = leases.findActiveByEnvironmentId(env.getEnvironmentId()).orElseThrow();
        setEndsIn(lease, remaining);
        return lease;
    }

    private void setEndsIn(EnvironmentLease lease, Duration remaining) {
        jdbcTemplate.update("UPDATE environment_lease SET ends_at = ? WHERE lease_id = ?",
                Timestamp.from(Instant.now().plus(remaining)), lease.getLeaseId());
    }

    /** Pretend every existing warning was sent {@code ago}. */
    private void backdateWarnings(Duration ago) {
        jdbcTemplate.update("UPDATE notification SET created_at = ? WHERE type = 'LEASE_EXPIRING'",
                Timestamp.from(Instant.now().minus(ago)));
    }

    private int warnings(User user) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE type = 'LEASE_EXPIRING' "
                + "AND user_id = ? AND entity_type = 'ENVIRONMENT' AND entity_id = ?", Integer.class,
                user.getUserId(), env.getEnvironmentId());
    }

    @Test
    void eachThresholdWarnsOnceAndAnExtensionReArmsThem() {
        EnvironmentLease lease = leaseEndingIn(Duration.ofMinutes(50));
        assertThat(leaseService.processLeaseWarnings()).isEqualTo(1);           // 60-minute warning
        assertThat(leaseService.processLeaseWarnings()).isZero();               // not again

        // 36 minutes later: 14 minutes left -> the 15-minute warning, once.
        backdateWarnings(Duration.ofMinutes(36));
        setEndsIn(lease, Duration.ofMinutes(14));
        assertThat(leaseService.processLeaseWarnings()).isEqualTo(1);
        assertThat(leaseService.processLeaseWarnings()).isZero();
        assertThat(warnings(alice)).isEqualTo(2);

        // Extended by an hour, then 50 minutes later: the 15-minute warning fires again.
        backdateWarnings(Duration.ofMinutes(60));
        setEndsIn(lease, Duration.ofMinutes(14));
        assertThat(leaseService.processLeaseWarnings()).isEqualTo(1);
        assertThat(warnings(alice)).isEqualTo(3);
    }

    @Test
    void theLockHolderAndRecentOperatorsAreWarnedButNotPeopleWhoLostAccess() {
        User bob = newUser("bob-warn@example.org", false, false);
        User carol = newUser("carol-warn@example.org", false, false);
        User dave = newUser("dave-warn@example.org", false, false);
        grant(bob, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        grant(carol, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        EnvironmentLease lease = leaseEndingIn(Duration.ofMinutes(10));

        lockService.acquireLock(env.getEnvironmentId(), bob.getUserId(), "holding", null);
        for (User initiator : new User[]{carol, dave}) {
            OperationExecution execution = new OperationExecution();
            execution.setExecutionId(UUID.randomUUID().toString());
            execution.setEnvironment(env);
            execution.setOperationType(OperationType.START);
            execution.setInitiatedByUserId(initiator.getUserId());
            execution.setTotalTargets(0);
            executions.saveAndFlush(execution);
        }

        leaseService.processLeaseWarnings();
        assertThat(warnings(alice)).isEqualTo(1);
        assertThat(warnings(bob)).isEqualTo(1);
        assertThat(warnings(carol)).isEqualTo(1);
        assertThat(warnings(dave)).isZero(); // started something but has no access
        assertThat(lease.getLeaseId()).isNotNull();
    }
}
