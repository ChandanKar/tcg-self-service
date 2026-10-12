package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.SpotPolicy;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.eks.model.Nodegroup;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Converting a node group to a Spot twin and back (E31-T05). */
class SpotConversionServiceTest {

    private static final String ENV = "env-1";
    private static final CloudTarget TARGET = CloudTarget.of("us-east-1");

    private final VmRepository vmRepository = mock(VmRepository.class);
    private final VmGroupRepository groupRepository = mock(VmGroupRepository.class);
    private final EksCloudProviderService eks = mock(EksCloudProviderService.class);
    private final EksSyncService eksSync = mock(EksSyncService.class);
    private final SpotPolicyService policy = mock(SpotPolicyService.class);
    private final SecurityService security = mock(SecurityService.class);
    private final LockService locks = mock(LockService.class);
    private final OperationExecutionRepository executions = mock(OperationExecutionRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private SpotConversionService service;
    private Environment env;
    private Vm source;
    private Vm twin;

    @BeforeEach
    void setUp() {
        PricingReferenceService pricing = new PricingReferenceService();
        pricing.loadPricing();
        service = new SpotConversionService(vmRepository, groupRepository, eks, eksSync, policy, security, locks, executions,
                pricing, audit);
        service.setNotificationService(notifications);
        ReflectionTestUtils.setField(service, "conversionEnabled", true);
        when(policy.isEnabled()).thenReturn(true);
        env = new Environment();
        env.setEnvironmentId(ENV);
        env.setDisplayName("Dev EKS");
        env.setSpotPolicy(SpotPolicy.ALLOWED);
        source = nodeGroup("vm-src", "workers");
        source.setStatus(VmStatus.RUNNING);
        twin = nodeGroup("vm-twin", "workers-spot");
        twin.setPurchaseOption(PurchaseOption.SPOT);
        when(vmRepository.findById("vm-src")).thenReturn(Optional.of(source));
        when(vmRepository.findById("vm-twin")).thenReturn(Optional.of(twin));
        when(vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, "c1/workers-spot")).thenReturn(Optional.of(twin));
        when(eks.describeNodegroup(eq("c1"), eq("workers-spot"), any())).thenReturn(Nodegroup.builder().nodegroupName("workers-spot").build());
        when(eks.stopVm(anyString(), any(), anyBoolean())).thenReturn(done(true));
        when(eks.startVm(anyString(), any())).thenReturn(done(true));
    }

    private Vm nodeGroup(String id, String name) {
        VmGroup g = new VmGroup();
        g.setEnvironment(env);
        Vm vm = new Vm(id);
        vm.setGroup(g);
        vm.setDisplayName(name);
        vm.setProvider(CloudProvider.AWS_EKS);
        vm.setProviderVmId("c1/" + name);
        vm.setRegion("us-east-1");
        vm.setMetadata("{\"desiredSize\":3,\"instanceType\":\"m6i.large\"}");
        return vm;
    }

    private static CompletableFuture<CloudProviderService.VmOperationResult> done(boolean ok) {
        return CompletableFuture.completedFuture(ok ? CloudProviderService.VmOperationResult.success("r", VmStatus.RUNNING)
                : CloudProviderService.VmOperationResult.failure("no capacity"));
    }

    @Test
    void aTwinWithDiversifiedTypesTakesOverBeforeTheOriginalIsScaledToZero() {
        service.convertToSpot(ENV, "vm-src", "user-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> types = ArgumentCaptor.forClass(List.class);
        InOrder order = inOrder(eks, eksSync);
        order.verify(eks).createSpotTwin(eq(TARGET), eq("c1"), eq("workers"), types.capture());
        order.verify(eksSync).upsertNodeGroup(eq(env), eq("c1"), eq("workers-spot"), eq("us-east-1"), any(), any());
        order.verify(eks).stopVm("c1/workers", TARGET, false);
        assertThat(types.getValue()).hasSizeGreaterThanOrEqualTo(3).startsWith("m6i.large");
        assertThat(source.isStandby()).isTrue();
        assertThat(source.getSpotPartnerVmId()).isEqualTo("vm-twin");
        assertThat(source.getStatus()).isEqualTo(VmStatus.STOPPED);
        assertThat(twin.getSpotPartnerVmId()).isEqualTo("vm-src");
        verify(audit).logEnvironmentAction(eq("user-1"), eq(AuditAction.SPOT_CONVERSION_COMPLETED), eq(ENV), any(), any(), any(), any(), any());
        verify(notifications).notifySpotOutcome(eq("user-1"), eq("vm-src"), eq(true), anyString(), anyString());
    }

    @Test
    void aTwinThatNeverBecomesReadyIsDeletedAndTheOriginalKeepsRunning() {
        doThrow(new IllegalStateException("Spot node group workers-spot did not become ready (STARTING)"))
                .when(eks).createSpotTwin(any(), anyString(), anyString(), anyList());

        service.convertToSpot(ENV, "vm-src", "user-1");

        verify(eks).deleteNodegroup(TARGET, "c1", "workers-spot");
        verify(eks, never()).stopVm(anyString(), any(), anyBoolean());
        assertThat(source.isStandby()).isFalse();
        verify(audit).logEnvironmentFailure(eq("user-1"), eq(AuditAction.SPOT_CONVERSION_FAILED), eq(ENV), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.contains("keeps running"));
    }

    @Test
    void conversionNeedsTheSwitchesTheEnvironmentPolicyAndARunningNodeGroup() {
        env.setSpotPolicy(SpotPolicy.NONE);
        assertThatThrownBy(() -> service.convertToSpot(ENV, "vm-src", "user-1")).isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not allow Spot");

        env.setSpotPolicy(SpotPolicy.ALLOWED);
        source.setStatus(VmStatus.STOPPED);
        assertThatThrownBy(() -> service.convertToSpot(ENV, "vm-src", "user-1")).isInstanceOf(ValidationException.class)
                .hasMessageContaining("must be running");

        ReflectionTestUtils.setField(service, "conversionEnabled", false);
        assertThatThrownBy(() -> service.convertToSpot(ENV, "vm-src", "user-1")).isInstanceOf(ValidationException.class)
                .hasMessageContaining("turned off");
        verify(eks, never()).createSpotTwin(any(), anyString(), anyString(), anyList());
    }

    @Test
    void goingBackStartsTheStandbyBeforeTheTwinIsRemoved() {
        source.setStandby(true);
        source.setSpotPartnerVmId("vm-twin");
        twin.setSpotPartnerVmId("vm-src");

        service.revertToOnDemand(ENV, "vm-twin", "user-1");

        InOrder order = inOrder(eks);
        order.verify(eks).startVm("c1/workers", TARGET);
        order.verify(eks).stopVm("c1/workers-spot", TARGET, false);
        order.verify(eks).deleteNodegroup(TARGET, "c1", "workers-spot");
        assertThat(source.isStandby()).isFalse();
        assertThat(source.getSpotPartnerVmId()).isNull();
        assertThat(twin.getIsActive()).isFalse();
    }

    @Test
    void ifTheStandbyWillNotStartTheTwinKeepsRunning() {
        source.setStandby(true);
        source.setSpotPartnerVmId("vm-twin");
        twin.setSpotPartnerVmId("vm-src");
        when(eks.startVm(anyString(), any())).thenReturn(done(false));

        service.revertToOnDemand(ENV, "vm-src", "user-1");

        verify(eks, never()).deleteNodegroup(any(), anyString(), anyString());
        assertThat(source.isStandby()).isTrue();
        verify(audit).logEnvironmentFailure(eq("user-1"), eq(AuditAction.SPOT_CONVERSION_FAILED), eq(ENV), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.contains("keeps running"));
    }
}
