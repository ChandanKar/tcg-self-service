package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLease;
import com.tcgdigital.vmcontrol.model.LeaseStatus;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * An overdue lease stops its environment, dependents first, as a system operation attributed to
 * "Lease expired" (E15-T05). The cloud provider is mocked. Not @Transactional.
 */
@TestPropertySource(properties = "lease.enabled=true")
class LeaseExpiryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private LeaseService leaseService;
    @Autowired private LeaseExpiryProcessor processor;
    @Autowired private LockService lockService;
    @Autowired private EnvironmentAccessService accessService;
    @Autowired private EnvironmentLeaseRepository leases;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User alice;
    private User bob;
    private User admin;
    private Environment env;
    private VmGroup database;
    private VmGroup web;

    @BeforeEach
    void setUp() {
        when(awsCloudProviderService.stopVm(anyString(), anyString(), anyBoolean(), any())).thenReturn(
                CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.STOPPED)));
        alice = newUser("alice-expiry@example.org", false, false);
        bob = newUser("bob-expiry@example.org", false, false);
        admin = newUser("admin-expiry@example.org", true, false);
        env = newEnvironment("Expiring");
        env.setLeaseEnabled(true);
        env = environmentRepository.saveAndFlush(env);
        grant(alice, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        grant(bob, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        database = newGroup(env, "database");
        web = newGroup(env, "web");
        web.setDependsOnGroupIds("[\"" + database.getGroupId() + "\"]");
        web = vmGroupRepository.saveAndFlush(web);
    }

    private EnvironmentLease overdueLease() {
        leaseService.openOrJoinOnStart(env.getEnvironmentId(), alice.getUserId(), 60);
        EnvironmentLease lease = leases.findActiveByEnvironmentId(env.getEnvironmentId()).orElseThrow();
        jdbcTemplate.update("UPDATE environment_lease SET ends_at = ? WHERE lease_id = ?",
                Timestamp.from(Instant.now().minusSeconds(30)), lease.getLeaseId());
        return lease;
    }

    private List<OperationExecution> stops() {
        return executions.findAll().stream()
                .filter(e -> e.getEnvironment().getEnvironmentId().equals(env.getEnvironmentId()))
                .filter(e -> OperationType.STOP.name().equalsIgnoreCase(String.valueOf(e.getOperationType())))
                .toList();
    }

    private int audit(String action, String detailLike) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action_type = ? AND environment_id = ? "
                + "AND details LIKE ?", Integer.class, action, env.getEnvironmentId(), detailLike);
    }

    private void awaitIdle() {
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM operation_execution WHERE "
                + "environment_id = ? AND status IN ('pending', 'in_progress')", Integer.class, env.getEnvironmentId())).isZero());
    }

    @Test
    void anOverdueLeaseStopsTheEnvironmentDependentsFirst() {
        Vm db = newVm(database, "db", VmStatus.RUNNING);
        Vm app = newVm(web, "app", VmStatus.RUNNING);
        EnvironmentLease lease = overdueLease();

        assertThat(processor.expire(lease.getLeaseId())).isTrue();

        EnvironmentLease expired = leases.findById(lease.getLeaseId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(LeaseStatus.EXPIRED);
        assertThat(expired.getEndReason()).isEqualTo("Lease expired");
        assertThat(stops()).hasSize(1);
        assertThat(expired.getStopExecutionId()).isEqualTo(stops().get(0).getExecutionId());
        List<String> order = jdbcTemplate.queryForList("SELECT target_id FROM operation_detail WHERE execution_id = ? "
                + "ORDER BY sequence_position", String.class, expired.getStopExecutionId());
        assertThat(order).containsExactly(app.getVmId(), db.getVmId());
        awaitAsync(() -> {
            assertThat(audit("LEASE_EXPIRED", "%stopping%")).isEqualTo(1);
            assertThat(audit("OPERATION_STARTED", "%Lease expired%")).isEqualTo(1);
        });
        awaitIdle();
    }

    @Test
    void theStopRunsEvenIfTheStarterLostAccessAndSomeoneElseHoldsTheLock() {
        newVm(database, "db", VmStatus.RUNNING);
        EnvironmentLease lease = overdueLease();
        accessService.revokeAccess(env.getEnvironmentId(), alice.getUserId(), admin.getUserId());
        lockService.acquireLock(env.getEnvironmentId(), bob.getUserId(), "my turn", null);

        assertThat(processor.expire(lease.getLeaseId())).isTrue();

        assertThat(stops()).hasSize(1);
        assertThat(lockService.getCurrentLock(env.getEnvironmentId())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM lock_history WHERE environment_id = ? "
                + "AND notes LIKE '%lease expired%'", Integer.class, env.getEnvironmentId())).isEqualTo(1);
        awaitIdle();
    }

    @Test
    void whileAnOperationRunsTheSweepRetriesThenGivesUp() {
        newVm(database, "db", VmStatus.RUNNING);
        EnvironmentLease lease = overdueLease();
        OperationExecution running = new OperationExecution();
        running.setExecutionId(UUID.randomUUID().toString());
        running.setEnvironment(env);
        running.setOperationType(OperationType.START);
        running.setInitiatedByUserId(bob.getUserId());
        running.setStatus(com.tcgdigital.vmcontrol.model.ExecutionStatus.IN_PROGRESS);
        executions.saveAndFlush(running);

        assertThat(processor.expire(lease.getLeaseId())).isFalse();
        assertThat(leases.findById(lease.getLeaseId()).orElseThrow().getStopAttempts()).isEqualTo(1);
        assertThat(stops()).isEmpty();

        jdbcTemplate.update("UPDATE environment_lease SET stop_attempts = 9 WHERE lease_id = ?", lease.getLeaseId());
        assertThat(processor.expire(lease.getLeaseId())).isTrue();
        EnvironmentLease gaveUp = leases.findById(lease.getLeaseId()).orElseThrow();
        assertThat(gaveUp.getStatus()).isEqualTo(LeaseStatus.EXPIRED);
        assertThat(gaveUp.getEndReason()).isEqualTo("Could not stop: operation in progress");
        assertThat(stops()).isEmpty();
    }

    @Test
    void whenNothingRunsTheLeaseExpiresWithoutAnOperation() {
        newVm(database, "db", VmStatus.STOPPED);
        EnvironmentLease lease = overdueLease();
        assertThat(processor.expire(lease.getLeaseId())).isTrue();
        assertThat(leases.findById(lease.getLeaseId()).orElseThrow().getStatus()).isEqualTo(LeaseStatus.EXPIRED);
        assertThat(stops()).isEmpty();
    }

    @Test
    void aLeaseThatIsNotOverdueIsLeftAlone() {
        newVm(database, "db", VmStatus.RUNNING);
        leaseService.openOrJoinOnStart(env.getEnvironmentId(), alice.getUserId(), 60);
        EnvironmentLease lease = leases.findActiveByEnvironmentId(env.getEnvironmentId()).orElseThrow();
        assertThat(processor.expire(lease.getLeaseId())).isFalse();
        assertThat(stops()).isEmpty();
    }

    @Test
    void twoProcessorsRacingExpireOnce() throws Exception {
        newVm(database, "db", VmStatus.RUNNING);
        EnvironmentLease lease = overdueLease();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    go.await();
                    try {
                        processor.expire(lease.getLeaseId());
                    } catch (RuntimeException expected) {
                        // the loser fails on the lease version or the active-operation check
                    }
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(stops()).hasSize(1);
        assertThat(leases.findById(lease.getLeaseId()).orElseThrow().getStatus()).isEqualTo(LeaseStatus.EXPIRED);
        awaitIdle();
    }
}
