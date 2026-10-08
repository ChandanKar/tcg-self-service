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
import com.tcgdigital.vmcontrol.repository.OperationDetailRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * STOP runs in reverse dependency order, RESTART is stop-then-start, continueOnFailure is
 * honoured and deactivated dependencies don't block (E05-T03). The AWS mock records every call
 * in order. Not @Transactional: executions run on worker threads.
 */
class OperationOrderingIntegrationTest extends AbstractIntegrationTest {

    @Autowired private VmOperationsService operationsService;
    @Autowired private LockService lockService;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private OperationDetailRepository details;

    private Environment env;
    private User user;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> nameByProviderId = new HashMap<>();
    private final Set<String> failing = new HashSet<>();

    @BeforeEach
    void setUp() {
        env = newEnvironment("Ordering");
        user = newUser("ordering@example.com", false, false);
        grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "testing", null);

        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.UNKNOWN);
        when(awsCloudProviderService.startVm(anyString(), anyString(), any())).thenAnswer(inv ->
                record("start", inv.getArgument(0), VmStatus.RUNNING));
        when(awsCloudProviderService.stopVm(anyString(), anyString(), anyBoolean(), any())).thenAnswer(inv ->
                record("stop", inv.getArgument(0), VmStatus.STOPPED));
    }

    private CompletableFuture<CloudProviderService.VmOperationResult> record(String action, String providerId,
                                                                              VmStatus result) {
        String name = nameByProviderId.get(providerId);
        calls.add(action + ":" + name);
        return CompletableFuture.completedFuture(failing.contains(action + ":" + name)
                ? CloudProviderService.VmOperationResult.failure("provider error")
                : CloudProviderService.VmOperationResult.success("req", result));
    }

    private Vm vm(VmGroup group, String name, VmStatus status, Vm... dependsOn) {
        Vm vm = newVm(group, name, status);
        vm.setName(name); // newVm suffixes the name; keep it readable for the call log
        if (dependsOn.length > 0) {
            vm.setDependencies(java.util.Arrays.stream(dependsOn).map(Vm::getVmId).toList());
        }
        vm = vmRepository.saveAndFlush(vm);
        nameByProviderId.put(vm.getProviderVmId(), name);
        return vm;
    }

    private OperationExecution run(OperationType type, boolean continueOnFailure, List<String> vmIds) throws Exception {
        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(type);
        dto.setSkipAlreadyInTargetState(false);
        dto.setContinueOnFailure(continueOnFailure);
        if (vmIds != null) {
            dto.setVmIds(vmIds);
        }
        String id = operationsService.startOperation(env.getEnvironmentId(), user.getUserId(), dto).getExecutionId();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            OperationExecution e = executions.findById(id).orElseThrow();
            if (e.getStatus() != ExecutionStatus.PENDING && e.getStatus() != ExecutionStatus.IN_PROGRESS) {
                return e;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("execution did not finish");
    }

    private Map<String, OperationDetail> detailsByStep(OperationExecution execution) {
        Map<String, OperationDetail> byStep = new HashMap<>();
        for (OperationDetail d : details.findAll()) {
            if (d.getExecution().getExecutionId().equals(execution.getExecutionId())) {
                byStep.put(d.getAction() + ":" + nameByProviderId.get(
                        vmRepository.findById(d.getTargetId()).orElseThrow().getProviderVmId()), d);
            }
        }
        return byStep;
    }

    @Test
    void stopAllStopsEveryAppVmBeforeTheDatabaseGroup() throws Exception {
        VmGroup dbGroup = newGroup(env, "db");
        VmGroup appGroup = newGroup(env, "app");
        appGroup.setDependencies(List.of(dbGroup.getGroupId()));
        vmGroupRepository.saveAndFlush(appGroup);
        vm(dbGroup, "db", VmStatus.RUNNING);
        vm(appGroup, "app1", VmStatus.RUNNING);
        vm(appGroup, "app2", VmStatus.RUNNING);

        OperationExecution done = run(OperationType.STOP, true, null);

        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(calls).hasSize(3);
        assertThat(calls.get(2)).isEqualTo("stop:db");
        assertThat(calls.subList(0, 2)).containsExactlyInAnyOrder("stop:app1", "stop:app2");
    }

    @Test
    void aFailedAppStopLeavesTheDatabaseRunningWithAClearReason() throws Exception {
        VmGroup group = newGroup(env, "stack");
        Vm db = vm(group, "db", VmStatus.RUNNING);
        vm(group, "app", VmStatus.RUNNING, db);
        failing.add("stop:app");

        OperationExecution done = run(OperationType.STOP, true, null);

        assertThat(calls).containsExactly("stop:app");
        OperationDetail dbStep = detailsByStep(done).get("stop:db");
        assertThat(dbStep.getStatus()).isEqualTo("skipped");
        assertThat(dbStep.getErrorMessage()).isEqualTo("Skipped: 'app' depends on this VM and did not stop");
        assertThat(vmRepository.findById(db.getVmId()).orElseThrow().getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(done.getFailedTargets()).isEqualTo(1);
        assertThat(done.getSkippedTargets()).isEqualTo(1);
    }

    @Test
    void restartStopsDependentsFirstThenStartsPrerequisitesFirst() throws Exception {
        VmGroup group = newGroup(env, "stack");
        Vm db = vm(group, "db", VmStatus.RUNNING);
        vm(group, "app", VmStatus.RUNNING, db);

        OperationExecution done = run(OperationType.RESTART, true, null);

        assertThat(calls).containsExactly("stop:app", "stop:db", "start:db", "start:app");
        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(done.getTotalTargets()).isEqualTo(4);
        assertThat(done.getCompletedTargets()).isEqualTo(4);
    }

    @Test
    void restartDoesNotStartAVmWhoseStopFailed() throws Exception {
        VmGroup group = newGroup(env, "single");
        vm(group, "solo", VmStatus.RUNNING);
        failing.add("stop:solo");

        OperationExecution done = run(OperationType.RESTART, true, null);

        assertThat(calls).containsExactly("stop:solo");
        assertThat(detailsByStep(done).get("start:solo").getErrorMessage()).isEqualTo("Skipped: 'solo' did not stop");
    }

    @Test
    void continueOnFailureOffSkipsEveryLaterWave() throws Exception {
        VmGroup group = newGroup(env, "waves");
        vm(group, "x", VmStatus.STOPPED);           // wave 1, fails
        Vm y = vm(group, "y", VmStatus.STOPPED);    // wave 1, succeeds
        vm(group, "z", VmStatus.STOPPED, y);        // wave 2, depends only on y
        failing.add("start:x");

        OperationExecution done = run(OperationType.START, false, null);

        assertThat(calls).containsExactlyInAnyOrder("start:x", "start:y");
        OperationDetail z = detailsByStep(done).get("start:z");
        assertThat(z.getStatus()).isEqualTo("skipped");
        assertThat(z.getErrorMessage()).isEqualTo("Skipped: an earlier step failed and continue-on-failure is off");
        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.PARTIAL_SUCCESS);
    }

    @Test
    void continueOnFailureOnRunsIndependentLaterWaves() throws Exception {
        VmGroup group = newGroup(env, "waves");
        vm(group, "x", VmStatus.STOPPED);
        Vm y = vm(group, "y", VmStatus.STOPPED);
        vm(group, "z", VmStatus.STOPPED, y);
        failing.add("start:x");

        OperationExecution done = run(OperationType.START, true, null);

        assertThat(calls).containsExactlyInAnyOrder("start:x", "start:y", "start:z");
        assertThat(done.getCompletedTargets()).isEqualTo(2);
        assertThat(done.getFailedTargets()).isEqualTo(1);
    }

    @Test
    void aDeactivatedDependencyDoesNotBlockAStart() throws Exception {
        VmGroup group = newGroup(env, "legacy");
        Vm retired = vm(group, "retired", VmStatus.STOPPED);
        retired.setIsActive(false);
        vmRepository.saveAndFlush(retired);
        Vm app = vm(group, "app", VmStatus.STOPPED, retired);

        OperationExecution done = run(OperationType.START, true, List.of(app.getVmId()));

        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(calls).containsExactly("start:app");
    }
}
