package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.OperationDetail;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.OperationDetailRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.PageRequest;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Executions orphaned by a restart are recovered at startup and before new operations, while a
 * run that is still heartbeating is never touched (E05-T06, H12). Not @Transactional.
 */
class OperationRecoveryServiceTest extends AbstractIntegrationTest {

    @Autowired private OperationRecoveryService recovery;
    @Autowired private VmOperationsService operationsService;
    @Autowired private LockService lockService;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private OperationDetailRepository details;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private ConfigurableApplicationContext context;

    private Environment env;
    private Vm vm;
    private User user;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Recovery");
        VmGroup group = newGroup(env, "app");
        vm = newVm(group, "app-1", VmStatus.STOPPED);
        user = newUser("recovery@example.com", false, false);
        grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "testing", null);
        when(awsCloudProviderService.startVm(anyString(), anyString(), any())).thenReturn(CompletableFuture
                .completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.RUNNING)));
    }

    /** An IN_PROGRESS execution with one in-progress step, as a crashed worker would leave it. */
    private OperationExecution inFlight(String executorId, Instant heartbeat) {
        OperationExecution execution = new OperationExecution();
        execution.setExecutionId(UUID.randomUUID().toString());
        execution.setEnvironment(env);
        execution.setOperationType(OperationType.START);
        execution.setStatus(ExecutionStatus.IN_PROGRESS);
        execution.setInitiatedByUserId(user.getUserId());
        execution.setStartedAt(Timestamp.from(heartbeat != null ? heartbeat : Instant.now().minus(1, ChronoUnit.HOURS)));
        execution.setLastHeartbeatAt(heartbeat != null ? Timestamp.from(heartbeat) : null);
        execution.setExecutorId(executorId);
        execution.setTotalTargets(1);
        execution = executions.saveAndFlush(execution);
        OperationDetail detail = new OperationDetail();
        detail.setDetailId(UUID.randomUUID().toString());
        detail.setExecution(execution);
        detail.setTargetType("vm");
        detail.setTargetId(vm.getVmId());
        detail.setTargetName(vm.getName());
        detail.setAction("start");
        detail.setStatus("in_progress");
        detail.setSequencePosition(1);
        details.saveAndFlush(detail);
        return execution;
    }

    private String thisHostWithAnotherPid() {
        String self = recovery.executorId();
        return self.substring(0, self.lastIndexOf('-') + 1) + "999999999";
    }

    private OperationExecution reload(OperationExecution e) {
        return executions.findById(e.getExecutionId()).orElseThrow();
    }

    private StartOperationDTO startAll() {
        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);
        return dto;
    }

    @Test
    void startupFailsThisHostsRunFromAPreviousProcessAndTheEnvironmentAcceptsANewOperation() {
        OperationExecution interrupted = inFlight(thisHostWithAnotherPid(), Instant.now()); // fresh, but dead

        events.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));

        OperationExecution after = reload(interrupted);
        assertThat(after.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(after.getErrorMessage()).startsWith("Interrupted");
        assertThat(details.findByExecutionExecutionIdAndStatusOrderBySequencePositionAsc(interrupted.getExecutionId(), "failed"))
                .singleElement()
                .satisfies(d -> assertThat(d.getErrorMessage()).startsWith("Interrupted"));
        assertThat(after.getFailedTargets()).isEqualTo(1);
        assertThat(auditLogs.findByActionOrderByCreatedAtDesc("OPERATION_FAILED", PageRequest.of(0, 10)).getContent())
                .anySatisfy(a -> assertThat(a.getTargetId()).isEqualTo(interrupted.getExecutionId()));

        assertThat(operationsService.startOperation(env.getEnvironmentId(), user.getUserId(), startAll())).isNotNull();
    }

    @Test
    void aRunWithoutHeartbeatForLongerThanTheLimitIsFailedWhenANewOperationStarts() {
        OperationExecution stale = inFlight("other-host-4242", Instant.now().minus(45, ChronoUnit.MINUTES));

        OperationExecution fresh = operationsService.startOperation(env.getEnvironmentId(), user.getUserId(), startAll());

        assertThat(fresh.getExecutionId()).isNotEqualTo(stale.getExecutionId());
        OperationExecution after = reload(stale);
        assertThat(after.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(after.getErrorMessage()).isEqualTo("Interrupted: no progress for 30 minutes (application restart?)");
    }

    @Test
    void aPendingRunThatNeverStartedIsStaleFromItsStartTime() {
        OperationExecution neverStarted = inFlight(null, null); // no heartbeat, started 1 h ago
        executions.transitionStatus(neverStarted.getExecutionId(), java.util.List.of("in_progress"), "pending", null, null);

        assertThat(recovery.failStale(env.getEnvironmentId())).isEqualTo(1);
        assertThat(reload(neverStarted).getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    void aRunStillHeartbeatingOnAnotherNodeIsNeverTouched() {
        OperationExecution healthy = inFlight("other-host-4242", Instant.now().minus(2, ChronoUnit.MINUTES));

        events.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
        assertThatThrownBy(() -> operationsService.startOperation(env.getEnvironmentId(), user.getUserId(), startAll()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already in progress");

        assertThat(reload(healthy).getStatus()).isEqualTo(ExecutionStatus.IN_PROGRESS);
    }

    @Test
    void aRunningExecutionRecordsItsNodeAndHeartbeat() throws Exception {
        CountDownLatch inProvider = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(awsCloudProviderService.startVm(anyString(), anyString(), any())).thenAnswer(inv -> {
            inProvider.countDown();
            release.await(20, TimeUnit.SECONDS);
            return CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.RUNNING));
        });

        String executionId = operationsService.startOperation(env.getEnvironmentId(), user.getUserId(), startAll())
                .getExecutionId();
        assertThat(inProvider.await(20, TimeUnit.SECONDS)).isTrue();

        OperationExecution running = executions.findById(executionId).orElseThrow();
        assertThat(running.getExecutorId()).isEqualTo(recovery.executorId());
        assertThat(running.getLastHeartbeatAt()).isNotNull();
        assertThat(Duration.between(running.getLastHeartbeatAt().toInstant(), Instant.now()).abs())
                .isLessThan(Duration.ofMinutes(1));
        assertThat(recovery.failStale(env.getEnvironmentId())).isZero();
        release.countDown();
    }
}
