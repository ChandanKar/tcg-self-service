package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
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
import org.springframework.data.domain.PageRequest;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The execution state machine is race-free and its final status is honest (E05-T02, M8).
 * Not @Transactional: executions run on worker threads and commit as in production. The AWS
 * provider is the base class's Mockito mock.
 */
class OperationStateMachineIntegrationTest extends AbstractIntegrationTest {

    @Autowired private VmOperationsService operationsService;
    @Autowired private LockService lockService;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private OperationDetailRepository details;
    @Autowired private AuditLogRepository auditLogs;

    private Environment env;
    private VmGroup group;
    private User user;

    @BeforeEach
    void setUpEnvironment() {
        env = newEnvironment("State machine");
        group = newGroup(env, "app");
        user = newUser("state-machine@example.com", false, false);
        grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "testing", null);
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.STOPPED);
    }

    private static CompletableFuture<CloudProviderService.VmOperationResult> ok() {
        return CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("req-1", VmStatus.RUNNING));
    }

    private static CompletableFuture<CloudProviderService.VmOperationResult> fails() {
        return CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.failure("InsufficientInstanceCapacity"));
    }

    private void startSucceedsFor(Vm vm) {
        when(awsCloudProviderService.startVm(eq(vm.getProviderVmId()), anyString(), any())).thenReturn(ok());
    }

    private void startFailsFor(Vm vm) {
        when(awsCloudProviderService.startVm(eq(vm.getProviderVmId()), anyString(), any())).thenReturn(fails());
    }

    private OperationExecution start() {
        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);
        return operationsService.startOperation(env.getEnvironmentId(), user.getUserId(), dto);
    }

    private static final Set<ExecutionStatus> FINAL = Set.of(ExecutionStatus.COMPLETED, ExecutionStatus.FAILED,
            ExecutionStatus.PARTIAL_SUCCESS, ExecutionStatus.CANCELLED);

    private OperationExecution awaitFinal(String executionId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            OperationExecution e = executions.findById(executionId).orElseThrow();
            if (FINAL.contains(e.getStatus())) {
                return e;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Execution " + executionId + " did not finish in 30s");
    }

    @Test
    void allStepsFailingIsFailedNotPartialSuccess() throws Exception {
        for (int i = 0; i < 3; i++) {
            startFailsFor(newVm(group, "vm" + i, VmStatus.STOPPED));
        }

        OperationExecution done = awaitFinal(start().getExecutionId());

        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(done.getCompletedTargets()).isZero();
        assertThat(done.getFailedTargets()).isEqualTo(3);
        assertThat(done.getSkippedTargets()).isZero();
        assertThat(done.getErrorMessage()).isEqualTo("All 3 steps failed");
        assertThat(auditLogs.findByActionOrderByCreatedAtDesc("OPERATION_FAILED", PageRequest.of(0, 5)).getContent())
                .anySatisfy(a -> assertThat(a.getTargetId()).isEqualTo(done.getExecutionId()));
    }

    @Test
    void dependentsOfAFailedStepAreSkippedNotFailed() throws Exception {
        Vm db = newVm(group, "db", VmStatus.STOPPED);
        Vm api = newVm(group, "api", VmStatus.STOPPED);
        Vm web = newVm(group, "web", VmStatus.STOPPED);
        api.setDependencies(List.of(db.getVmId()));
        web.setDependencies(List.of(db.getVmId()));
        vmRepository.saveAllAndFlush(List.of(api, web));
        startFailsFor(db);
        startSucceedsFor(api);
        startSucceedsFor(web);

        OperationExecution done = awaitFinal(start().getExecutionId());

        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(done.getCompletedTargets()).isZero();
        assertThat(done.getFailedTargets()).isEqualTo(1);
        assertThat(done.getSkippedTargets()).isEqualTo(2);
        assertThat(done.getErrorMessage()).isEqualTo("No step succeeded (1 failed, 2 skipped)");
        verify(awsCloudProviderService, never()).startVm(eq(api.getProviderVmId()), anyString(), any());
    }

    @Test
    void oneSuccessAndOneFailureIsPartialSuccess() throws Exception {
        startSucceedsFor(newVm(group, "ok", VmStatus.STOPPED));
        startFailsFor(newVm(group, "bad", VmStatus.STOPPED));

        OperationExecution done = awaitFinal(start().getExecutionId());

        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.PARTIAL_SUCCESS);
        assertThat(done.getCompletedTargets()).isEqualTo(1);
        assertThat(done.getFailedTargets()).isEqualTo(1);
        assertThat(done.getSkippedTargets()).isZero();
    }

    @Test
    void allStepsSucceedingIsCompleted() throws Exception {
        startSucceedsFor(newVm(group, "a", VmStatus.STOPPED));
        startSucceedsFor(newVm(group, "b", VmStatus.STOPPED));

        OperationExecution done = awaitFinal(start().getExecutionId());

        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(done.getCompletedTargets()).isEqualTo(2);
        assertThat(done.getFailedTargets()).isZero();
    }

    /** A PENDING execution with one pending step, inserted directly (no worker started). */
    private OperationExecution pendingExecution(Vm vm) {
        OperationExecution execution = new OperationExecution();
        execution.setExecutionId(UUID.randomUUID().toString());
        execution.setEnvironment(env);
        execution.setOperationType(OperationType.START);
        execution.setStatus(ExecutionStatus.PENDING);
        execution.setInitiatedByUserId(user.getUserId());
        execution.setStartedAt(new Timestamp(System.currentTimeMillis()));
        execution.setTotalTargets(1);
        execution = executions.saveAndFlush(execution);
        OperationDetail detail = new OperationDetail();
        detail.setDetailId(UUID.randomUUID().toString());
        detail.setExecution(execution);
        detail.setTargetType("vm");
        detail.setTargetId(vm.getVmId());
        detail.setTargetName(vm.getName());
        detail.setAction("start");
        detail.setStatus("pending");
        detail.setSequencePosition(1);
        details.saveAndFlush(detail);
        return execution;
    }

    @Test
    void cancelledWhilePendingTheWorkerNeverStartsIt() throws Exception {
        Vm vm = newVm(group, "never", VmStatus.STOPPED);
        startSucceedsFor(vm);
        OperationExecution pending = pendingExecution(vm);

        operationsService.cancelExecution(env.getEnvironmentId(), pending.getExecutionId(), user.getUserId());
        operationsService.executeOperationAsync(pending.getExecutionId(), false); // the worker arrives late

        verify(awsCloudProviderService, after(1500).never()).startVm(anyString(), anyString(), any());
        OperationExecution after = executions.findById(pending.getExecutionId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(details.findByExecutionExecutionIdAndStatusOrderBySequencePositionAsc(pending.getExecutionId(), "cancelled"))
                .hasSize(1);
    }

    @Test
    void aCancelThatLandsDuringTheLastWaveStaysCancelledAndSendsNoCompletion() throws Exception {
        Vm vm = newVm(group, "slow", VmStatus.STOPPED);
        CountDownLatch providerCalled = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(awsCloudProviderService.startVm(eq(vm.getProviderVmId()), anyString(), any())).thenAnswer(inv -> {
            providerCalled.countDown();
            release.await(20, TimeUnit.SECONDS);
            return ok();
        });

        String executionId = start().getExecutionId();
        assertThat(providerCalled.await(20, TimeUnit.SECONDS)).isTrue();
        operationsService.cancelExecution(env.getEnvironmentId(), executionId, user.getUserId());
        release.countDown(); // the step now succeeds, after the cancel

        Thread.sleep(1500); // let the worker finish its wave
        OperationExecution after = executions.findById(executionId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(auditLogs.findByActionOrderByCreatedAtDesc("OPERATION_COMPLETED", PageRequest.of(0, 20)).getContent())
                .noneSatisfy(a -> assertThat(a.getTargetId()).isEqualTo(executionId));
    }

    @Test
    void cancellingAFinishedRunIsRejected() throws Exception {
        startSucceedsFor(newVm(group, "quick", VmStatus.STOPPED));
        String executionId = awaitFinal(start().getExecutionId()).getExecutionId();

        assertThatThrownBy(() -> operationsService.cancelExecution(env.getEnvironmentId(), executionId, user.getUserId()))
                .hasMessageContaining("Cannot cancel");
        assertThat(executions.findById(executionId).orElseThrow().getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
    }

    // ---------------------------------------------------------------- repository

    @Test
    void transitionStatusOnlyMovesFromTheGivenStatusesAndBumpsTheVersion() {
        Vm vm = newVm(group, "repo", VmStatus.STOPPED);
        OperationExecution pending = pendingExecution(vm);
        long before = pending.getVersion();

        int wrongFrom = executions.transitionStatus(pending.getExecutionId(), List.of("in_progress"), "completed", null, null);
        int moved = executions.transitionStatus(pending.getExecutionId(), List.of("pending"), "in_progress", null, null);

        assertThat(wrongFrom).isZero();
        assertThat(moved).isEqualTo(1);
        OperationExecution after = executions.findById(pending.getExecutionId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ExecutionStatus.IN_PROGRESS);
        assertThat(after.getVersion()).isEqualTo(before + 1);
    }

    @Test
    void aStaleEntitySaveFailsInsteadOfOverwritingANewerStatus() {
        Vm vm = newVm(group, "stale", VmStatus.STOPPED);
        OperationExecution stale = pendingExecution(vm);
        executions.transitionStatus(stale.getExecutionId(), List.of("pending"), "cancelled", null, "cancelled");

        stale.setStatus(ExecutionStatus.IN_PROGRESS);
        assertThatThrownBy(() -> executions.saveAndFlush(stale))
                .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        assertThat(executions.findById(stale.getExecutionId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.CANCELLED);
    }
}
