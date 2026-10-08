package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resize checks the live state and running operations, and never reports failure once AWS has
 * the new instance type (E05-T09, LOW-OPS-RESIZE).
 */
@ExtendWith(MockitoExtension.class)
class VmResizeServiceTest {

    @Mock private VmRepository vmRepository;
    @Mock private VmInventorySnapshotRepository snapshots;
    @Mock private AwsCloudProviderService aws;
    @Mock private LockService lockService;
    @Mock private AuditService auditService;
    @Mock private OperationExecutionRepository executions;

    private VmResizeService service;
    private Vm vm;

    @BeforeEach
    void setUp() {
        service = new VmResizeService(vmRepository, snapshots, aws, lockService, auditService, executions);
        Environment env = new Environment();
        env.setEnvironmentId("env-1");
        env.setDisplayName("Env 1");
        VmGroup group = new VmGroup();
        group.setEnvironment(env);
        vm = new Vm();
        vm.setVmId("vm-1");
        vm.setGroup(group);
        vm.setProvider(CloudProvider.AWS);
        vm.setProviderVmId("i-123");
        vm.setRegion("ap-south-1");
        vm.setStatus(VmStatus.STOPPED);
        vm.setDisplayName("app-1");
        when(vmRepository.findById("vm-1")).thenReturn(Optional.of(vm));
        lenient().when(snapshots.findByVmVmId("vm-1")).thenReturn(Optional.empty());
    }

    @Test
    void awsSaysRunningWhileTheDatabaseSaysStopped_rejectsAndCorrectsTheDatabase() {
        when(aws.getVmStatus("i-123", "ap-south-1")).thenReturn(VmStatus.RUNNING);

        assertThatThrownBy(() -> service.applyInstanceTypeChange("vm-1", "t3.large", "user-1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageStartingWith("VM must be stopped");

        verify(vmRepository).updateStatusIfCurrent(eq("vm-1"), eq(VmStatus.STOPPED), eq(VmStatus.RUNNING), any());
        verify(aws, never()).changeInstanceType(anyString(), anyString(), anyString());
    }

    @Test
    void aRunningOperationInTheEnvironmentBlocksTheResize() {
        when(executions.hasActiveOperations("env-1")).thenReturn(true);

        assertThatThrownBy(() -> service.applyInstanceTypeChange("vm-1", "t3.large", "user-1"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("An operation is running in this environment");

        verify(aws, never()).changeInstanceType(anyString(), anyString(), anyString());
    }

    @Test
    void aSuccessfulChangeWithoutASnapshotCreatesOneAndIsAudited() {
        when(aws.getVmStatus("i-123", "ap-south-1")).thenReturn(VmStatus.STOPPED);

        service.applyInstanceTypeChange("vm-1", "t3.large", "user-1");

        ArgumentCaptor<VmInventorySnapshot> saved = ArgumentCaptor.forClass(VmInventorySnapshot.class);
        verify(snapshots).save(saved.capture());
        assertThat(saved.getValue().getInstanceType()).isEqualTo("t3.large");
        assertThat(saved.getValue().getVm()).isSameAs(vm);
        assertThat(saved.getValue().getProviderVmId()).isEqualTo("i-123");
        verify(auditService).logChange("user-1", AuditAction.VM_RESIZE_COMPLETED, "vm", "vm-1", "app-1",
                null, "t3.large", null);
        verify(auditService, never()).logEnvironmentFailure(any(), eq(AuditAction.VM_RESIZE_FAILED), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void anErrorAfterAwsAppliedTheTypeIsNotReportedAsFailure() {
        when(aws.getVmStatus("i-123", "ap-south-1")).thenReturn(VmStatus.STOPPED);
        doThrow(new RuntimeException("Read timed out")).when(aws).changeInstanceType("ap-south-1", "i-123", "t3.large");
        when(aws.getInstanceType("ap-south-1", "i-123")).thenReturn("t3.large");

        assertThatCode(() -> service.applyInstanceTypeChange("vm-1", "t3.large", "user-1")).doesNotThrowAnyException();

        verify(auditService).logChange(eq("user-1"), eq(AuditAction.VM_RESIZE_COMPLETED), any(), any(), any(),
                any(), eq("t3.large"), any());
        verify(auditService, never()).logEnvironmentFailure(any(), eq(AuditAction.VM_RESIZE_FAILED), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void aRealFailureIsReportedAndAudited() {
        when(aws.getVmStatus("i-123", "ap-south-1")).thenReturn(VmStatus.STOPPED);
        doThrow(new RuntimeException("Unsupported instance type")).when(aws)
                .changeInstanceType("ap-south-1", "i-123", "t9.huge");
        when(aws.getInstanceType("ap-south-1", "i-123")).thenReturn("t3.medium");

        assertThatThrownBy(() -> service.applyInstanceTypeChange("vm-1", "t9.huge", "user-1"))
                .hasMessage("Unsupported instance type");

        verify(auditService).logEnvironmentFailure("user-1", AuditAction.VM_RESIZE_FAILED, "env-1", "Env 1",
                "vm", "vm-1", "app-1", "Unsupported instance type");
        verify(snapshots, never()).save(any());
    }

    @Test
    void aSnapshotWriteFailureAfterTheChangeStillSucceeds() {
        when(aws.getVmStatus("i-123", "ap-south-1")).thenReturn(VmStatus.STOPPED);
        when(snapshots.save(any())).thenThrow(new RuntimeException("deadlock"));

        assertThatCode(() -> service.applyInstanceTypeChange("vm-1", "t3.large", "user-1")).doesNotThrowAnyException();

        verify(auditService).logChange(eq("user-1"), eq(AuditAction.VM_RESIZE_COMPLETED), any(), any(), any(),
                any(), eq("t3.large"), any());
    }
}
