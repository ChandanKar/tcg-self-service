package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.DiscoveryFailedException;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VM discovery numbering and per-instance isolation (E09-T01, H8). Extended by later E09 tasks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VmDiscoveryServiceTest {

    @Mock private EnvironmentRepository environmentRepository;
    @Mock private VmGroupRepository vmGroupRepository;
    @Mock private VmRepository vmRepository;
    @Mock private AwsCloudProviderService awsService;
    @Mock private AuditService auditService;
    @Mock private TagReconciliationService tagReconciliationService;

    private VmDiscoveryService service;
    private Environment env;
    private VmGroup group;

    @BeforeEach
    void setUp() {
        service = new VmDiscoveryService(environmentRepository, vmGroupRepository, vmRepository, awsService,
                auditService, new ObjectMapper(), tagReconciliationService);
        ReflectionTestUtils.setField(service, "discoveryStrategy", "tag");
        ReflectionTestUtils.setField(service, "envTagKey", "tcg:environment");
        ReflectionTestUtils.setField(service, "namePrefix", "app");
        ReflectionTestUtils.setField(service, "defaultRegion", "ap-south-1");

        env = new Environment();
        env.setEnvironmentId("env-1");
        env.setName("app");
        env.setIsActive(true);
        env.setMetadata("{\"region\":\"ap-south-1\"}");
        group = new VmGroup();
        group.setGroupId("group-1");
        group.setEnvironment(env);
        group.setName("discovered");

        when(awsService.isAvailable()).thenReturn(true);
        when(environmentRepository.findActiveEc2Environments()).thenReturn(List.of(env));
        when(environmentRepository.findByName("app")).thenReturn(Optional.of(env));
        when(vmGroupRepository.findByEnvironmentEnvironmentIdAndName("env-1", "discovered")).thenReturn(Optional.of(group));
        when(vmRepository.existsByProviderAndProviderVmId(eq(CloudProvider.AWS), anyString())).thenReturn(false);
        when(vmRepository.findByGroupGroupIdOrderBySequencePositionAsc("group-1")).thenReturn(List.of());
        when(vmRepository.save(any(Vm.class))).then(returnsFirstArg());
    }

    private static Instance instance(String id, String name) {
        return Instance.builder().instanceId(id).tags(Tag.builder().key("Name").value(name).build()).build();
    }

    private List<Vm> saved(int count) {
        ArgumentCaptor<Vm> captor = ArgumentCaptor.forClass(Vm.class);
        verify(vmRepository, times(count)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void aNewTaggedInstanceIsNumberedAboveTheHighestPositionIncludingInactiveVms() {
        // Positions 1,2,3 exist and VM 2 is inactive: two active VMs, but the highest is 3.
        when(vmRepository.countByGroupGroupId("group-1")).thenReturn(2L);
        when(vmRepository.findMaxSequencePositionByGroupId("group-1")).thenReturn(3);
        when(awsService.discoverTaggedInstances("ap-south-1", "tcg:environment", "app"))
                .thenReturn(List.of(instance("i-0000000000000new1", "web")));

        int registered = service.discoverAndRegisterVms();

        assertThat(registered).isEqualTo(1);
        assertThat(saved(1).get(0).getSequencePosition()).isEqualTo(4);
    }

    @Test
    void anEmptyGroupStartsAtOne() {
        when(vmRepository.findMaxSequencePositionByGroupId("group-1")).thenReturn(null);
        when(awsService.discoverTaggedInstances("ap-south-1", "tcg:environment", "app"))
                .thenReturn(List.of(instance("i-0000000000000new1", "web")));

        service.discoverAndRegisterVms();

        assertThat(saved(1).get(0).getSequencePosition()).isEqualTo(1);
    }

    @Test
    void oneInstanceFailingToSaveDoesNotStopTheNext() {
        when(vmRepository.findMaxSequencePositionByGroupId("group-1")).thenReturn(0);
        when(vmRepository.save(any(Vm.class)))
                .thenThrow(new IllegalStateException("duplicate key"))
                .then(returnsFirstArg());
        when(awsService.discoverTaggedInstances("ap-south-1", "tcg:environment", "app"))
                .thenReturn(List.of(instance("i-0000000000000bad1", "bad"), instance("i-0000000000000good", "good")));

        int registered = service.discoverAndRegisterVms();

        assertThat(registered).isEqualTo(1);
        assertThat(saved(2).get(1).getProviderVmId()).isEqualTo("i-0000000000000good");
        // Missing-VM flagging and tag reconciliation still run after a failed instance.
        verify(vmRepository).findByGroupGroupIdOrderBySequencePositionAsc("group-1");
        verify(tagReconciliationService).reconcileEnvironment(env);
    }

    // ------------------------------------------------------------------ AWS failures (E09-T02, H24)

    private static DiscoveryFailedException throttled() {
        return new DiscoveryFailedException("EC2 discovery failed in ap-south-1", new RuntimeException("Throttling"));
    }

    @Test
    void aFailedAwsCallRegistersFlagsAndRetagsNothing() {
        when(awsService.discoverTaggedInstances("ap-south-1", "tcg:environment", "app")).thenThrow(throttled());

        assertThat(service.discoverAndRegisterVms()).isZero();

        verify(vmRepository, never()).save(any(Vm.class));
        verify(vmRepository, never()).markDriftIfActive(any(), any());
        verify(tagReconciliationService, never()).reconcileEnvironment(any());
    }

    @Test
    void oneEnvironmentFailingDoesNotStopTheNext() {
        Environment other = new Environment();
        other.setEnvironmentId("env-2");
        other.setName("other");
        other.setIsActive(true);
        other.setMetadata("{\"region\":\"ap-south-1\"}");
        VmGroup otherGroup = new VmGroup();
        otherGroup.setGroupId("group-2");
        otherGroup.setEnvironment(other);
        when(environmentRepository.findActiveEc2Environments()).thenReturn(List.of(env, other));
        when(vmGroupRepository.findByEnvironmentEnvironmentIdAndName("env-2", "discovered")).thenReturn(Optional.of(otherGroup));
        when(awsService.discoverTaggedInstances("ap-south-1", "tcg:environment", "app")).thenThrow(throttled());
        when(awsService.discoverTaggedInstances("ap-south-1", "tcg:environment", "other"))
                .thenReturn(List.of(instance("i-00000000000other", "web")));

        assertThat(service.discoverAndRegisterVms()).isEqualTo(1);
        assertThat(saved(1).get(0).getGroup().getGroupId()).isEqualTo("group-2");
    }

    @Test
    void anActiveVmMissingFromASuccessfulCallIsFlaggedByTheNarrowUpdate() {
        Vm active = new Vm();
        active.setVmId("vm-active");
        active.setProviderVmId("i-0000000000000gone");
        active.setIsActive(true);
        Vm inactive = new Vm();
        inactive.setVmId("vm-inactive");
        inactive.setProviderVmId("i-000000000000gone2");
        inactive.setIsActive(false);
        when(vmRepository.findByGroupGroupIdOrderBySequencePositionAsc("group-1")).thenReturn(List.of(active, inactive));
        when(awsService.discoverTaggedInstances("ap-south-1", "tcg:environment", "app")).thenReturn(List.of());

        service.discoverAndRegisterVms();

        verify(vmRepository).markDriftIfActive(eq("vm-active"), any());
        verify(vmRepository, never()).markDriftIfActive(eq("vm-inactive"), any());
        verify(vmRepository, never()).save(any(Vm.class));
    }

    @Test
    void aFailedNamePatternCallDoesNothing() {
        ReflectionTestUtils.setField(service, "discoveryStrategy", "name-pattern");
        when(awsService.discoverInstancesByNamePrefix("ap-south-1", "app")).thenThrow(throttled());

        assertThat(service.discoverAndRegisterVms()).isZero();

        verify(vmRepository, never()).markDriftIfActive(any(), any());
        verify(environmentRepository, never()).findByName(any());
    }

    @Test
    void aNamePatternInstanceKeepsItsNumberWhenFree() {
        ReflectionTestUtils.setField(service, "discoveryStrategy", "name-pattern");
        when(awsService.discoverInstancesByNamePrefix("ap-south-1", "app")).thenReturn(List.of(instance("i-00000000000app07", "app-7")));
        when(vmRepository.existsByGroupGroupIdAndSequencePosition("group-1", 7)).thenReturn(false);

        int registered = service.discoverAndRegisterVms();

        assertThat(registered).isEqualTo(1);
        assertThat(saved(1).get(0).getSequencePosition()).isEqualTo(7);
    }

    @Test
    void aNamePatternInstanceWhoseNumberIsTakenGoesAboveTheHighest() {
        ReflectionTestUtils.setField(service, "discoveryStrategy", "name-pattern");
        when(awsService.discoverInstancesByNamePrefix("ap-south-1", "app")).thenReturn(List.of(instance("i-00000000000app07", "app-7")));
        when(vmRepository.existsByGroupGroupIdAndSequencePosition(eq("group-1"), anyInt())).thenReturn(true);
        when(vmRepository.findMaxSequencePositionByGroupId("group-1")).thenReturn(9);

        service.discoverAndRegisterVms();

        assertThat(saved(1).get(0).getSequencePosition()).isEqualTo(10);
    }

    @Test
    void oneNamePatternInstanceFailingDoesNotStopTheNext() {
        ReflectionTestUtils.setField(service, "discoveryStrategy", "name-pattern");
        when(awsService.discoverInstancesByNamePrefix("ap-south-1", "app"))
                .thenReturn(List.of(instance("i-00000000000app01", "app-1"), instance("i-00000000000app02", "app-2")));
        when(vmRepository.save(any(Vm.class)))
                .thenThrow(new IllegalStateException("duplicate key"))
                .then(returnsFirstArg());

        assertThat(service.discoverAndRegisterVms()).isEqualTo(1);
        verify(tagReconciliationService).reconcileEnvironment(env);
    }
}
