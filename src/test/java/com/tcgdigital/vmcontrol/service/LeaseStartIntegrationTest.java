package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLease;
import com.tcgdigital.vmcontrol.model.EnvironmentLeaseParticipant;
import com.tcgdigital.vmcontrol.model.LeaseStatus;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseParticipantRepository;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Starting a lease-enabled environment opens or joins its lease; stopping all of it ends the
 * lease (E15-T02). Not @Transactional: the hooks run after commit.
 */
@TestPropertySource(properties = "lease.enabled=true")
class LeaseStartIntegrationTest extends AbstractIntegrationTest {

    @Autowired private VmOperationsService operations;
    @Autowired private LeaseService leaseService;
    @Autowired private EnvironmentLeaseRepository leases;
    @Autowired private EnvironmentLeaseParticipantRepository participants;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User alice;
    private User bob;

    @BeforeEach
    void stubCloud() {
        when(awsCloudProviderService.startVm(anyString(), anyString(), any())).thenReturn(
                CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.RUNNING)));
        when(awsCloudProviderService.stopVm(anyString(), anyString(), anyBoolean(), any())).thenReturn(
                CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.STOPPED)));
        alice = newUser("alice-lease@example.org", false, false);
        bob = newUser("bob-lease@example.org", false, false);
    }

    private Environment leaseEnvironment(Integer defaultMinutes, Integer maxMinutes, boolean enabled) {
        Environment env = newEnvironment("Lease start");
        env.setLeaseEnabled(enabled);
        env.setLeaseDefaultMinutes(defaultMinutes);
        env.setLeaseMaxMinutes(maxMinutes);
        env = environmentRepository.saveAndFlush(env);
        VmGroup group = newGroup(env, "web");
        newVm(group, "vm1", VmStatus.STOPPED);
        grant(alice, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        grant(bob, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        return env;
    }

    private void start(Environment env, User user, Integer leaseMinutes) {
        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);
        dto.setLeaseMinutes(leaseMinutes);
        operations.startOperation(env.getEnvironmentId(), user.getUserId(), dto);
    }

    private void awaitOperationsDone(Environment env) {
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM operation_execution WHERE "
                + "environment_id = ? AND status IN ('PENDING', 'IN_PROGRESS')", Integer.class, env.getEnvironmentId())).isZero());
    }

    private EnvironmentLease activeLease(Environment env) {
        return leases.findActiveByEnvironmentId(env.getEnvironmentId()).orElseThrow();
    }

    private List<String> activeParticipants(EnvironmentLease lease) {
        return participants.findByLeaseIdAndLeftAtIsNullOrderByJoinedAtAsc(lease.getLeaseId()).stream()
                .map(EnvironmentLeaseParticipant::getUserId).toList();
    }

    @Test
    void startingOpensALeaseForTheDefaultLength() {
        Environment env = leaseEnvironment(240, null, true);
        start(env, alice, null);

        EnvironmentLease lease = activeLease(env);
        assertThat(lease.getEndsAt().toInstant()).isCloseTo(Instant.now().plus(Duration.ofMinutes(240)),
                within(1, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(lease.getStartedByUserId()).isEqualTo(alice.getUserId());
        assertThat(activeParticipants(lease)).containsExactly(alice.getUserId());
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE "
                + "action_type = 'LEASE_STARTED' AND environment_id = ?", Integer.class, env.getEnvironmentId())).isEqualTo(1));
        awaitOperationsDone(env);
    }

    @Test
    void aSecondStarterJoinsAndMovesTheEndLaterButNeverPastTheMaximum() {
        Environment env = leaseEnvironment(60, 180, true);
        start(env, alice, null);
        awaitOperationsDone(env);

        start(env, bob, 120);
        EnvironmentLease lease = activeLease(env);
        assertThat(activeParticipants(lease)).containsExactlyInAnyOrder(alice.getUserId(), bob.getUserId());
        assertThat(lease.getEndsAt().toInstant()).isCloseTo(Instant.now().plus(Duration.ofMinutes(120)),
                within(1, java.time.temporal.ChronoUnit.MINUTES));
        awaitOperationsDone(env);

        // Started 170 minutes ago and ending in 5: a 120-minute join is capped at started + 180.
        jdbcTemplate.update("UPDATE environment_lease SET started_at = ?, ends_at = ? WHERE lease_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(170))),
                Timestamp.from(Instant.now().plus(Duration.ofMinutes(5))), lease.getLeaseId());
        leaseService.openOrJoinOnStart(env.getEnvironmentId(), alice.getUserId(), 120);
        assertThat(activeLease(env).getEndsAt().toInstant()).isCloseTo(Instant.now().plus(Duration.ofMinutes(10)),
                within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    void askingForMoreThanTheMaximumFailsBeforeAnyOperationExists() {
        Environment env = leaseEnvironment(60, 120, true);
        assertThatThrownBy(() -> start(env, alice, 180)).isInstanceOf(ValidationException.class);
        assertThat(executions.findAll()).noneMatch(e -> e.getEnvironment().getEnvironmentId().equals(env.getEnvironmentId()));
        assertThat(leases.findActiveByEnvironmentId(env.getEnvironmentId())).isEmpty();
    }

    @Test
    void anEnvironmentThatDidNotOptInGetsNoLease() {
        Environment env = leaseEnvironment(60, 120, false);
        start(env, alice, null);
        awaitOperationsDone(env);
        assertThat(leases.findActiveByEnvironmentId(env.getEnvironmentId())).isEmpty();
    }

    @Test
    void stoppingTheWholeEnvironmentEndsTheLeaseButAPartialStopDoesNot() {
        Environment env = leaseEnvironment(60, 120, true);
        start(env, alice, null);
        awaitOperationsDone(env);
        String leaseId = activeLease(env).getLeaseId();

        StartOperationDTO partial = new StartOperationDTO();
        partial.setOperationType(OperationType.STOP);
        partial.setSkipAlreadyInTargetState(false);
        partial.setGroupIds(List.of(vmGroupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(
                env.getEnvironmentId()).get(0).getGroupId()));
        operations.startOperation(env.getEnvironmentId(), alice.getUserId(), partial);
        awaitOperationsDone(env);
        assertThat(leases.findActiveByEnvironmentId(env.getEnvironmentId())).isPresent();

        StartOperationDTO all = new StartOperationDTO();
        all.setOperationType(OperationType.STOP);
        all.setSkipAlreadyInTargetState(false);
        operations.startOperation(env.getEnvironmentId(), alice.getUserId(), all);
        EnvironmentLease ended = leases.findById(leaseId).orElseThrow();
        assertThat(ended.getStatus()).isEqualTo(LeaseStatus.ENDED);
        assertThat(ended.getEndReason()).startsWith("Stopped by");
        awaitOperationsDone(env);
    }

    @Test
    void concurrentStartsShareOneLease() throws Exception {
        Environment env = leaseEnvironment(60, 120, true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (User user : List.of(alice, bob)) {
                pool.submit(() -> {
                    go.await();
                    leaseService.openOrJoinOnStart(env.getEnvironmentId(), user.getUserId(), null);
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM environment_lease WHERE environment_id = ?",
                Integer.class, env.getEnvironmentId())).isEqualTo(1);
        assertThat(activeParticipants(activeLease(env))).containsExactlyInAnyOrder(alice.getUserId(), bob.getUserId());
    }
}
