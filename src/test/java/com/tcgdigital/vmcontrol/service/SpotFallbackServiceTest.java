package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** On-demand fallback for Spot twins (E31-T06): grace, start once, clear after two healthy checks. */
class SpotFallbackServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    private final VmRepository vmRepository = mock(VmRepository.class);
    private final EksCloudProviderService eks = mock(EksCloudProviderService.class);
    private final OperationExecutionRepository executions = mock(OperationExecutionRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private SpotFallbackService service;
    private Vm twin;
    private Vm standby;

    @BeforeEach
    void setUp() {
        service = new SpotFallbackService(vmRepository, eks, executions, audit);
        service.setNotificationService(notifications);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "enabled", true);
        Environment env = new Environment();
        env.setEnvironmentId("env-1");
        env.setDisplayName("Dev EKS");
        VmGroup g = new VmGroup();
        g.setEnvironment(env);
        twin = new Vm("vm-twin");
        twin.setGroup(g);
        twin.setDisplayName("workers-spot");
        twin.setProvider(CloudProvider.AWS_EKS);
        twin.setProviderVmId("c1/workers-spot");
        twin.setRegion("us-east-1");
        twin.setPurchaseOption(PurchaseOption.SPOT);
        twin.setSpotPartnerVmId("vm-src");
        twin.setStatus(VmStatus.RUNNING);
        standby = new Vm("vm-src");
        standby.setGroup(g);
        standby.setDisplayName("workers");
        standby.setProvider(CloudProvider.AWS_EKS);
        standby.setProviderVmId("c1/workers");
        standby.setRegion("us-east-1");
        standby.setStandby(true);
        standby.setStatus(VmStatus.STOPPED);
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(twin, standby));
        when(vmRepository.findById("vm-src")).thenReturn(Optional.of(standby));
        when(eks.startVm(anyString(), any())).thenReturn(CompletableFuture.completedFuture(
                CloudProviderService.VmOperationResult.success("r", VmStatus.RUNNING)));
        when(eks.stopVm(anyString(), any(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(
                CloudProviderService.VmOperationResult.success("r", VmStatus.STOPPED)));
    }

    private void health(int desired, int running, String... issues) {
        when(eks.getNodegroupHealth(any(), eq("c1"), eq("workers-spot")))
                .thenReturn(new EksCloudProviderService.NodegroupHealth(List.of(issues), desired, running));
    }

    @Test
    void shortOfCapacityForLessThanTheGraceDoesNothingButRemembersSince() {
        health(3, 1);

        assertThat(service.evaluate()).isZero();

        verify(eks, never()).startVm(anyString(), any());
        verify(vmRepository).updateSpotFallbackState("vm-twin", Timestamp.from(NOW), false, 0);
    }

    @Test
    void beyondTheGraceTheStandbyStartsOnceAndAdminsAreTold() {
        health(3, 0, "AsgInstanceLaunchFailures");
        twin.setSpotDegradedSince(Timestamp.from(NOW.minus(Duration.ofMinutes(11))));

        assertThat(service.evaluate()).isEqualTo(1);

        verify(eks).startVm("c1/workers", CloudTarget.of("us-east-1"));
        verify(vmRepository).updateSpotFallbackState(eq("vm-twin"), any(), eq(true), eq(0));
        verify(audit).logEnvironmentAction(isNull(), eq(AuditAction.SPOT_FALLBACK_ACTIVATED), eq("env-1"), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.contains("AsgInstanceLaunchFailures"));
        verify(notifications).notifySpotFallback(eq("env-1"), eq("vm-twin"), eq(true), anyString(), anyString());

        // the next run, still short: no second start
        twin.setSpotFallbackActive(true);
        assertThat(service.evaluate()).isZero();
        verify(eks, times(1)).startVm(anyString(), any());
    }

    @Test
    void afterTwoHealthyChecksTheStandbyIsScaledBackToZero() {
        health(3, 3);
        twin.setSpotFallbackActive(true);
        standby.setStatus(VmStatus.RUNNING);

        assertThat(service.evaluate()).isZero();
        verify(vmRepository).updateSpotFallbackState("vm-twin", null, true, 1);
        verify(eks, never()).stopVm(anyString(), any(), anyBoolean());

        twin.setSpotHealthyChecks(1);
        assertThat(service.evaluate()).isEqualTo(1);
        verify(eks).stopVm("c1/workers", CloudTarget.of("us-east-1"), false);
        verify(vmRepository).updateSpotFallbackState("vm-twin", null, false, 0);
        verify(audit).logEnvironmentAction(isNull(), eq(AuditAction.SPOT_FALLBACK_CLEARED), eq("env-1"), any(), any(), any(), any(), any());
    }

    @Test
    void nothingHappensWhileAnOperationRunsOrWhenTheEnvironmentIsStopped() {
        health(3, 0);
        twin.setSpotDegradedSince(Timestamp.from(NOW.minus(Duration.ofHours(1))));
        when(executions.hasActiveOperations("env-1")).thenReturn(true);
        assertThat(service.evaluate()).isZero();

        when(executions.hasActiveOperations("env-1")).thenReturn(false);
        twin.setStatus(VmStatus.STOPPED);
        assertThat(service.evaluate()).isZero();

        verify(eks, never()).startVm(anyString(), any());
        verify(eks, never()).getNodegroupHealth(any(), anyString(), anyString());
        verify(vmRepository, never()).updateSpotFallbackState(anyString(), any(), anyBoolean(), anyInt());
    }

    @Test
    void capacityIssuesCountEvenAtFullSize() {
        assertThat(SpotFallbackService.degraded(new EksCloudProviderService.NodegroupHealth(
                List.of("Ec2SpotInsufficientCapacity"), 3, 3))).isTrue();
        assertThat(SpotFallbackService.degraded(new EksCloudProviderService.NodegroupHealth(List.of(), 3, 3))).isFalse();
        assertThat(SpotFallbackService.degraded(new EksCloudProviderService.NodegroupHealth(List.of(), 0, 0))).isFalse();
    }
}
