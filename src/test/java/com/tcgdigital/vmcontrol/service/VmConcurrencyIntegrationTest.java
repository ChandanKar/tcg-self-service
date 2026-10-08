package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.controller.GlobalExceptionHandler;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * VM writes no longer lose concurrent updates (E05-T04, H14): optimistic locking on Vm,
 * conditional status updates, targeted metadata/tag writes. Not @Transactional.
 */
class VmConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired private VmOperationsService operationsService;
    @Autowired private StateSyncService stateSyncService;
    @Autowired private LockService lockService;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private AuditLogRepository auditLogs;

    private Environment env;
    private VmGroup group;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Concurrency");
        group = newGroup(env, "nodes");
    }

    private Vm reload(Vm vm) {
        return vmRepository.findById(vm.getVmId()).orElseThrow();
    }

    // ---------------------------------------------------------------- repository contract

    @Test
    void updateStatusIfCurrentMovesOnlyFromTheExpectedStatusAndBumpsTheVersion() {
        Vm vm = newVm(group, "vm", VmStatus.RUNNING);
        long before = reload(vm).getVersion();

        int wrong = vmRepository.updateStatusIfCurrent(vm.getVmId(), VmStatus.STOPPED, VmStatus.STARTING,
                new Timestamp(System.currentTimeMillis()));
        assertThat(wrong).isZero();
        assertThat(reload(vm).getStatus()).isEqualTo(VmStatus.RUNNING);
        assertThat(reload(vm).getVersion()).isEqualTo(before);

        int moved = vmRepository.updateStatusIfCurrent(vm.getVmId(), VmStatus.RUNNING, VmStatus.STOPPING,
                new Timestamp(System.currentTimeMillis()));
        assertThat(moved).isEqualTo(1);
        assertThat(reload(vm).getStatus()).isEqualTo(VmStatus.STOPPING);
        assertThat(reload(vm).getVersion()).isEqualTo(before + 1);
    }

    @Test
    void aStaleVmSaveFailsInsteadOfOverwritingNewerData() {
        Vm vm = newVm(group, "vm", VmStatus.RUNNING);
        Vm stale = reload(vm);
        vmRepository.updateMetadata(vm.getVmId(), "{\"minSize\":3}", new Timestamp(System.currentTimeMillis()));

        stale.setDisplayName("renamed from a stale copy");
        assertThatThrownBy(() -> vmRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(reload(vm).getMetadata()).isEqualTo("{\"minSize\":3}");
    }

    @Test
    void tagsAreMarkedSyncedEvenWhenAVmChangedDuringTheRun() {
        Vm a = newVm(group, "a", VmStatus.RUNNING);
        Vm b = newVm(group, "b", VmStatus.RUNNING);
        // Changed by someone else after the reconciliation loaded it:
        vmRepository.updateStatusIfCurrent(b.getVmId(), VmStatus.RUNNING, VmStatus.STOPPING,
                new Timestamp(System.currentTimeMillis()));

        int marked = vmRepository.markTagsSynced(List.of(a.getVmId(), b.getVmId()),
                new Timestamp(System.currentTimeMillis()));

        assertThat(marked).isEqualTo(2);
        assertThat(reload(a).getTagsSyncedAt()).isNotNull();
        assertThat(reload(b).getTagsSyncedAt()).isNotNull();
        assertThat(reload(b).getStatus()).isEqualTo(VmStatus.STOPPING);
    }

    // ---------------------------------------------------------------- H14 end to end

    @Test
    void eksScalingSavedDuringAStopSurvivesTheStepsStatusWrite() throws Exception {
        User user = newUser("eks-op@example.com", false, false);
        grant(user, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        lockService.acquireLock(env.getEnvironmentId(), user.getUserId(), "testing", null);
        Vm nodes = newVm(group, "workers", VmStatus.RUNNING);
        nodes.setProvider(CloudProvider.AWS_EKS);
        nodes.setProviderVmId("cluster/workers");
        vmRepository.saveAndFlush(nodes);

        // Like EksCloudProviderService: save the node-group sizes, then scale to zero.
        when(eksCloudProviderService.stopVm(anyString(), anyString(), anyBoolean(), any())).thenAnswer(inv -> {
            vmRepository.updateMetadata(nodes.getVmId(), "{\"minSize\":3,\"desiredSize\":5}",
                    new Timestamp(System.currentTimeMillis()));
            return CompletableFuture.completedFuture(
                    CloudProviderService.VmOperationResult.success("upd", VmStatus.STOPPED));
        });

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.STOP);
        dto.setSkipAlreadyInTargetState(false);
        String executionId = operationsService.startOperation(env.getEnvironmentId(), user.getUserId(), dto)
                .getExecutionId();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (executions.findById(executionId).orElseThrow().getStatus() != ExecutionStatus.COMPLETED) {
            assertThat(Instant.now()).isBefore(deadline);
            Thread.sleep(100);
        }

        Vm after = reload(nodes);
        assertThat(after.getStatus()).isEqualTo(VmStatus.STOPPED);
        assertThat(after.getMetadata()).isEqualTo("{\"minSize\":3,\"desiredSize\":5}");
    }

    @Test
    void aStateSyncWithAStaleCopyDoesNotOverwriteAnOperationOrRecordFalseDrift() {
        Vm vm = newVm(group, "app", VmStatus.RUNNING);
        Vm syncCopy = reload(vm); // the sync loaded it while RUNNING
        // ...then an operation started stopping it:
        vmRepository.updateStatusIfCurrent(vm.getVmId(), VmStatus.RUNNING, VmStatus.STOPPING,
                new Timestamp(System.currentTimeMillis()));
        when(awsCloudProviderService.isAvailable()).thenReturn(true);
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.STOPPED);

        boolean drift = stateSyncService.syncVmState(syncCopy);

        assertThat(drift).isFalse();
        assertThat(reload(vm).getStatus()).isEqualTo(VmStatus.STOPPING);
        assertThat(auditLogs.findByActionOrderByCreatedAtDesc("STATE_DRIFT_DETECTED", PageRequest.of(0, 10)).getContent())
                .noneSatisfy(a -> assertThat(a.getTargetId()).isEqualTo(vm.getVmId()));
    }

    @Test
    void aStateSyncWithACurrentCopyStillRecordsRealDrift() {
        Vm vm = newVm(group, "app", VmStatus.RUNNING);
        when(awsCloudProviderService.isAvailable()).thenReturn(true);
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.STOPPED);

        // The real entry point (one transaction per environment, as the Monitoring sync button uses).
        assertThat(stateSyncService.syncEnvironmentVmStates(env.getEnvironmentId())).isEqualTo(1);

        Vm after = reload(vm);
        assertThat(after.getStatus()).isEqualTo(VmStatus.STOPPED);
        assertThat(after.getStateDriftDetected()).isTrue();
    }

    // ---------------------------------------------------------------- API mapping

    @Test
    void anOptimisticLockFailureIsAnHttp409() {
        var response = new GlobalExceptionHandler().handleOptimisticLock(
                new ObjectOptimisticLockingFailureException(Vm.class, "vm-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("message", "This item was changed by someone else. Reload and try again.");
    }
}
