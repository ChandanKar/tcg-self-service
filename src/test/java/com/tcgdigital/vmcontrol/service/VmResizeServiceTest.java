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
    @Mock private CostEstimationService costEstimationService;

    /** The type the cost page currently recommends for vm-1 (E08-T05). */
    private String recommended = "t3.large";

    private VmResizeService service;
    private Vm vm;

    @BeforeEach
    void setUp() {
        service = new VmResizeService(vmRepository, snapshots, aws, lockService, auditService, executions,
                costEstimationService);
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
        lenient().when(costEstimationService.findCandidate("vm-1")).thenAnswer(inv -> Optional.of(candidate(recommended)));
    }

    private static com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO candidate(String suggested) {
        return new com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO("vm-1", "app-1", "env-1", "Env 1",
                "t3.xlarge", suggested, null, null, null, null, null, true, "cpu-threshold-rule", null, "SCALE_DOWN", "STOPPED");
    }

    // ---- Only the current recommendation can be applied (E08-T05, M11) ----

    @Test
    void aTypeOtherThanTheCurrentRecommendationIsRejectedBeforeAnyAwsChange() {
        when(aws.getVmStatus("i-123", com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"))).thenReturn(VmStatus.STOPPED);

        assertThatThrownBy(() -> service.applyInstanceTypeChange("vm-1", "p4d.24xlarge", "user-1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not match the current recommendation");

        verify(aws, never()).changeInstanceType(any(), any(), any());
        verify(auditService, never()).logEnvironmentAction(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aVmWithNoCurrentRecommendationCannotBeResized() {
        when(aws.getVmStatus("i-123", com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"))).thenReturn(VmStatus.STOPPED);
        when(costEstimationService.findCandidate("vm-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyInstanceTypeChange("vm-1", "t3.large", "user-1"))
                .isInstanceOf(ValidationException.class);
        verify(aws, never()).changeInstanceType(any(), any(), any());
    }

    @Test
    void theRecommendedTypeIsAppliedAndTheCostPageRebuilds() {
        when(aws.getVmStatus("i-123", com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"))).thenReturn(VmStatus.STOPPED);

        service.applyInstanceTypeChange("vm-1", "t3.large", "user-1");

        verify(aws).changeInstanceType(com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"), "i-123", "t3.large");
        verify(costEstimationService).invalidateBundles();
    }

    @Test
    void awsSaysRunningWhileTheDatabaseSaysStopped_rejectsAndCorrectsTheDatabase() {
        when(aws.getVmStatus("i-123", com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"))).thenReturn(VmStatus.RUNNING);

        assertThatThrownBy(() -> service.applyInstanceTypeChange("vm-1", "t3.large", "user-1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageStartingWith("VM must be stopped");

        verify(vmRepository).updateStatusIfCurrent(eq("vm-1"), eq(VmStatus.STOPPED), eq(VmStatus.RUNNING), any());
        verify(aws, never()).changeInstanceType(org.mockito.ArgumentMatchers.any(com.tcgdigital.vmcontrol.service.CloudTarget.class), anyString(), anyString());
    }

    @Test
    void aRunningOperationInTheEnvironmentBlocksTheResize() {
        when(executions.hasActiveOperations("env-1")).thenReturn(true);

        assertThatThrownBy(() -> service.applyInstanceTypeChange("vm-1", "t3.large", "user-1"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("An operation is running in this environment");

        verify(aws, never()).changeInstanceType(org.mockito.ArgumentMatchers.any(com.tcgdigital.vmcontrol.service.CloudTarget.class), anyString(), anyString());
    }

    @Test
    void aSuccessfulChangeWithoutASnapshotCreatesOneAndIsAudited() {
        when(aws.getVmStatus("i-123", com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"))).thenReturn(VmStatus.STOPPED);

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
        when(aws.getVmStatus("i-123", com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"))).thenReturn(VmStatus.STOPPED);
        doThrow(new RuntimeException("Read timed out")).when(aws).changeInstanceType(com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"), "i-123", "t3.large");
        when(aws.getInstanceType("ap-south-1", "i-123")).thenReturn("t3.large");

        assertThatCode(() -> service.applyInstanceTypeChange("vm-1", "t3.large", "user-1")).doesNotThrowAnyException();

        verify(auditService).logChange(eq("user-1"), eq(AuditAction.VM_RESIZE_COMPLETED), any(), any(), any(),
                any(), eq("t3.large"), any());
        verify(auditService, never()).logEnvironmentFailure(any(), eq(AuditAction.VM_RESIZE_FAILED), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void aRealFailureIsReportedAndAudited() {
        when(aws.getVmStatus("i-123", com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"))).thenReturn(VmStatus.STOPPED);
        doThrow(new RuntimeException("Unsupported instance type")).when(aws)
                .changeInstanceType(com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"), "i-123", "t9.huge");
        when(aws.getInstanceType("ap-south-1", "i-123")).thenReturn("t3.medium");

        recommended = "t9.huge";
        assertThatThrownBy(() -> service.applyInstanceTypeChange("vm-1", "t9.huge", "user-1"))
                .hasMessage("Unsupported instance type");

        verify(auditService).logEnvironmentFailure("user-1", AuditAction.VM_RESIZE_FAILED, "env-1", "Env 1",
                "vm", "vm-1", "app-1", "Unsupported instance type");
        verify(snapshots, never()).save(any());
    }

    @Test
    void aSnapshotWriteFailureAfterTheChangeStillSucceeds() {
        when(aws.getVmStatus("i-123", com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"))).thenReturn(VmStatus.STOPPED);
        when(snapshots.save(any())).thenThrow(new RuntimeException("deadlock"));

        assertThatCode(() -> service.applyInstanceTypeChange("vm-1", "t3.large", "user-1")).doesNotThrowAnyException();

        verify(auditService).logChange(eq("user-1"), eq(AuditAction.VM_RESIZE_COMPLETED), any(), any(), any(),
                any(), eq("t3.large"), any());
    }

    // ---- Resize events for the savings ledger (E17-T04) ----

    @Test
    void aSuccessfulResizeIsRecordedAndAFailedOneIsNot() {
        com.tcgdigital.vmcontrol.repository.VmResizeEventRepository events =
                org.mockito.Mockito.mock(com.tcgdigital.vmcontrol.repository.VmResizeEventRepository.class);
        service.setResizeEventRepository(events);
        com.tcgdigital.vmcontrol.model.VmInventorySnapshot current = new com.tcgdigital.vmcontrol.model.VmInventorySnapshot();
        current.setInstanceType("t3.xlarge");
        when(snapshots.findByVmVmId("vm-1")).thenReturn(Optional.of(current));
        when(aws.getVmStatus("i-123", com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"))).thenReturn(VmStatus.STOPPED);

        service.applyInstanceTypeChange("vm-1", "t3.large", "user-1");
        org.mockito.ArgumentCaptor<com.tcgdigital.vmcontrol.model.VmResizeEvent> saved =
                org.mockito.ArgumentCaptor.forClass(com.tcgdigital.vmcontrol.model.VmResizeEvent.class);
        verify(events).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getNewInstanceType()).isEqualTo("t3.large");
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getSource())
                .isEqualTo(com.tcgdigital.vmcontrol.model.VmResizeEvent.Source.APPLY);

        org.mockito.Mockito.reset(events);
        doThrow(new RuntimeException("Unsupported instance type")).when(aws)
                .changeInstanceType(com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1"), "i-123", "t9.huge");
        when(aws.getInstanceType("ap-south-1", "i-123")).thenReturn("t3.medium");
        recommended = "t9.huge";
        assertThatThrownBy(() -> service.applyInstanceTypeChange("vm-1", "t9.huge", "user-1"));
        verify(events, never()).save(any());
    }

    // ---- Safe resize: stop, change, start, roll back (E30-T06) ----

    private static final com.tcgdigital.vmcontrol.service.CloudTarget TARGET = com.tcgdigital.vmcontrol.service.CloudTarget.of("ap-south-1");

    private final java.util.Map<String, com.tcgdigital.vmcontrol.model.VmResizeEvent> stored = new java.util.HashMap<>();
    private com.tcgdigital.vmcontrol.repository.VmResizeEventRepository resizes;
    private NotificationService notifications;

    private static java.util.concurrent.CompletableFuture<CloudProviderService.VmOperationResult> done(boolean ok, VmStatus status, String message) {
        return java.util.concurrent.CompletableFuture.completedFuture(ok ? CloudProviderService.VmOperationResult.success("r", status)
                : CloudProviderService.VmOperationResult.failure(message));
    }

    /** A running VM, safe resize on, every AWS check passing. */
    private void safeResizeOfARunningVm() {
        resizes = org.mockito.Mockito.mock(com.tcgdigital.vmcontrol.repository.VmResizeEventRepository.class);
        lenient().when(resizes.save(any())).thenAnswer(inv -> {
            com.tcgdigital.vmcontrol.model.VmResizeEvent e = inv.getArgument(0);
            stored.put(e.getEventId(), e);
            return e;
        });
        lenient().when(resizes.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(stored.get((String) inv.getArgument(0))));
        service.setResizeEventRepository(resizes);
        notifications = org.mockito.Mockito.mock(NotificationService.class);
        service.setNotificationService(notifications);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "safeResizeEnabled", true);
        vm.setStatus(VmStatus.RUNNING);
        lenient().when(aws.describeForResize(TARGET, "i-123"))
                .thenReturn(new AwsCloudProviderService.ResizeFacts("t3.xlarge", "ap-south-1a", "x86_64", true));
        lenient().when(aws.isInstanceTypeOffered(TARGET, "t3.large", "ap-south-1a")).thenReturn(true);
        lenient().when(aws.describeInstanceType(TARGET, "t3.large"))
                .thenReturn(new AwsCloudProviderService.InstanceTypeFacts(java.util.List.of("x86_64"), "required"));
        lenient().when(aws.getInstanceType("ap-south-1", "i-123")).thenReturn("t3.xlarge");
        lenient().when(aws.stopVm(eq("i-123"), eq(TARGET), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(done(true, VmStatus.STOPPED, null));
    }

    private com.tcgdigital.vmcontrol.model.VmResizeEvent theResize() {
        assertThat(stored).hasSize(1);
        return stored.values().iterator().next();
    }

    @Test
    void aRunningVmIsStoppedResizedAndStartedInThatOrder() {
        safeResizeOfARunningVm();
        when(aws.getVmStatus("i-123", TARGET)).thenReturn(VmStatus.RUNNING, VmStatus.STOPPED);
        when(aws.startVm("i-123", TARGET)).thenReturn(done(true, VmStatus.RUNNING, null));

        String id = service.resizeWithRestart("vm-1", "t3.large", "user-1");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(aws);
        order.verify(aws).stopVm("i-123", TARGET, false);
        order.verify(aws).changeInstanceType(TARGET, "i-123", "t3.large");
        order.verify(aws).startVm("i-123", TARGET);
        com.tcgdigital.vmcontrol.model.VmResizeEvent e = theResize();
        assertThat(e.getEventId()).isEqualTo(id);
        assertThat(e.getStatus()).isEqualTo(com.tcgdigital.vmcontrol.model.ResizeStatus.COMPLETED);
        assertThat(e.getOldInstanceType()).isEqualTo("t3.xlarge");
        assertThat(e.isRestartRequested()).isTrue();
        assertThat(e.getOriginalStatus()).isEqualTo("RUNNING");
        verify(auditService).logChange("user-1", AuditAction.VM_RESIZE_COMPLETED, "vm", "vm-1", "app-1", "t3.xlarge", "t3.large", null);
        verify(notifications).notifyResizeOutcome(eq("user-1"), eq("vm-1"), eq(true), anyString(), anyString());
        verify(costEstimationService).invalidateBundles();
    }

    @Test
    void aStartFailureOnTheNewTypeRevertsToTheOldTypeAndStartsAgain() {
        safeResizeOfARunningVm();
        when(aws.getVmStatus("i-123", TARGET)).thenReturn(VmStatus.RUNNING, VmStatus.STOPPED, VmStatus.STOPPED);
        when(aws.startVm("i-123", TARGET)).thenReturn(done(false, null, "InsufficientInstanceCapacity"),
                done(true, VmStatus.RUNNING, null));

        service.resizeWithRestart("vm-1", "t3.large", "user-1");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(aws);
        order.verify(aws).changeInstanceType(TARGET, "i-123", "t3.large");
        order.verify(aws).startVm("i-123", TARGET);
        order.verify(aws).changeInstanceType(TARGET, "i-123", "t3.xlarge");
        order.verify(aws).startVm("i-123", TARGET);
        com.tcgdigital.vmcontrol.model.VmResizeEvent e = theResize();
        assertThat(e.getStatus()).isEqualTo(com.tcgdigital.vmcontrol.model.ResizeStatus.ROLLED_BACK);
        assertThat(e.getErrorMessage()).contains("InsufficientInstanceCapacity");
        verify(auditService).logEnvironmentFailure(eq("user-1"), eq(AuditAction.VM_RESIZE_ROLLED_BACK), eq("env-1"), eq("Env 1"),
                eq("vm"), eq("vm-1"), eq("app-1"), anyString());
        verify(notifications).notifyResizeOutcome(eq("user-1"), eq("vm-1"), eq(false),
                org.mockito.ArgumentMatchers.contains("rolled back"), anyString());
        verify(auditService, never()).logChange(any(), eq(AuditAction.VM_RESIZE_COMPLETED), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aFailedRollbackEndsFailedAndTellsTheEnvironmentAdmins() {
        safeResizeOfARunningVm();
        when(aws.getVmStatus("i-123", TARGET)).thenReturn(VmStatus.RUNNING, VmStatus.STOPPED, VmStatus.STOPPED);
        when(aws.startVm("i-123", TARGET)).thenReturn(done(false, null, "InsufficientInstanceCapacity"),
                done(false, null, "still no capacity"));

        service.resizeWithRestart("vm-1", "t3.large", "user-1");

        com.tcgdigital.vmcontrol.model.VmResizeEvent e = theResize();
        assertThat(e.getStatus()).isEqualTo(com.tcgdigital.vmcontrol.model.ResizeStatus.FAILED);
        assertThat(e.getErrorMessage()).contains("still no capacity");
        verify(notifications).notifyResizeNeedsAttention(eq("env-1"), eq("vm-1"), anyString(), anyString());
    }

    @Test
    void aTypeNotOfferedInTheZoneIsRejectedBeforeAnyAwsChange() {
        safeResizeOfARunningVm();
        when(aws.getVmStatus("i-123", TARGET)).thenReturn(VmStatus.RUNNING);
        when(aws.isInstanceTypeOffered(TARGET, "t3.large", "ap-south-1a")).thenReturn(false);

        assertThatThrownBy(() -> service.resizeWithRestart("vm-1", "t3.large", "user-1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not offered in ap-south-1a");
        verify(aws, never()).stopVm(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(aws, never()).changeInstanceType(any(), anyString(), anyString());
        assertThat(stored).isEmpty();
    }

    @Test
    void aTargetNeedingEnaOnAnInstanceWithoutItIsRejected() {
        safeResizeOfARunningVm();
        when(aws.getVmStatus("i-123", TARGET)).thenReturn(VmStatus.RUNNING);
        when(aws.describeForResize(TARGET, "i-123"))
                .thenReturn(new AwsCloudProviderService.ResizeFacts("t3.xlarge", "ap-south-1a", "x86_64", false));

        assertThatThrownBy(() -> service.resizeWithRestart("vm-1", "t3.large", "user-1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("needs ENA");
        verify(aws, never()).changeInstanceType(any(), anyString(), anyString());
    }

    @Test
    void turnedOffTheSafeResizeIsRejected() {
        safeResizeOfARunningVm();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "safeResizeEnabled", false);
        when(aws.getVmStatus("i-123", TARGET)).thenReturn(VmStatus.RUNNING);

        assertThatThrownBy(() -> service.resizeWithRestart("vm-1", "t3.large", "user-1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("turned off");
        verify(aws, never()).stopVm(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aStoppedVmIsResizedWithoutBeingStarted() {
        safeResizeOfARunningVm();
        vm.setStatus(VmStatus.STOPPED);
        when(aws.getVmStatus("i-123", TARGET)).thenReturn(VmStatus.STOPPED);

        service.resizeWithRestart("vm-1", "t3.large", "user-1");

        verify(aws).changeInstanceType(TARGET, "i-123", "t3.large");
        verify(aws, never()).startVm(anyString(), any());
        verify(aws, never()).stopVm(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(theResize().getStatus()).isEqualTo(com.tcgdigital.vmcontrol.model.ResizeStatus.COMPLETED);
    }
}
