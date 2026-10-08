package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.DiscoveryFailedException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Discovery against MySQL: numbering respects the unique (group_id, sequence_position) index
 * with inactive rows present (E09-T01, H8); a VM missing from AWS is flagged by a narrow update
 * that leaves its other columns alone, and a failed AWS call flags nothing (E09-T02, H24, M5).
 */
class VmDiscoveryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private VmDiscoveryService discoveryService;

    private Environment discoveryEnvironment(String name) {
        Environment env = newEnvironment(name);
        env.setServiceType("EC2");
        env.setMetadata("{\"region\":\"ap-south-1\"}");
        return environmentRepository.saveAndFlush(env);
    }

    private VmGroup discoveryGroup(Environment env) {
        VmGroup group = newGroup(env, "discovered");
        group.setName("discovered");
        return vmGroupRepository.saveAndFlush(group);
    }

    private void awsReturnsNothingByDefault() {
        when(awsCloudProviderService.isAvailable()).thenReturn(true);
        when(awsCloudProviderService.discoverTaggedInstances(eq("ap-south-1"), anyString(), anyString())).thenReturn(List.of());
    }

    @Test
    void anInactiveVmInTheDiscoveryGroupDoesNotBlockNewRegistrations() {
        Environment env = discoveryEnvironment("Disc");
        VmGroup group = discoveryGroup(env);
        Vm one = newVm(group, "one", VmStatus.RUNNING);
        Vm two = newVm(group, "two", VmStatus.RUNNING);
        Vm three = newVm(group, "three", VmStatus.RUNNING);
        // Positions 1,2,3 with VM 2 inactive: two active VMs, so "count + 1" gave 3 and collided.
        int position = 1;
        for (Vm vm : List.of(one, two, three)) {
            vm.setSequencePosition(position++);
        }
        two.setIsActive(false);
        vmRepository.saveAllAndFlush(List.of(one, two, three));
        Instance live = Instance.builder().instanceId("i-0" + Long.toHexString(System.nanoTime()))
                .tags(Tag.builder().key("Name").value("fresh").build()).build();
        awsReturnsNothingByDefault();
        when(awsCloudProviderService.discoverTaggedInstances("ap-south-1", "tcg:environment", env.getName()))
                .thenReturn(List.of(live, Instance.builder().instanceId(one.getProviderVmId()).build(),
                        Instance.builder().instanceId(three.getProviderVmId()).build()));

        discoveryService.discoverAndRegisterVms();

        Vm registered = vmRepository.findByProviderAndProviderVmId(one.getProvider(), live.instanceId()).orElseThrow();
        assertThat(registered.getGroup().getGroupId()).isEqualTo(group.getGroupId());
        assertThat(registered.getSequencePosition()).isEqualTo(three.getSequencePosition() + 1);
    }

    @Test
    void aVmMissingFromAwsIsFlaggedWithoutTouchingItsOtherColumns() {
        Environment env = discoveryEnvironment("Drift");
        Vm gone = newVm(discoveryGroup(env), "gone", VmStatus.STOPPED);
        awsReturnsNothingByDefault();

        discoveryService.discoverAndRegisterVms();

        Vm after = vmRepository.findById(gone.getVmId()).orElseThrow();
        assertThat(after.getStateDriftDetected()).isTrue();
        assertThat(after.getStatus()).isEqualTo(VmStatus.STOPPED);
        assertThat(after.getName()).isEqualTo(gone.getName());
        assertThat(after.getIsActive()).isTrue();
    }

    @Test
    void tagReconciliationAfterDiscoveryLoadsOnlyUntaggedVmsOfTheEnvironment() {
        Environment env = discoveryEnvironment("Tags");
        VmGroup group = discoveryGroup(env);
        Vm tagged = newVm(group, "tagged", VmStatus.RUNNING);
        Vm fresh = newVm(group, "fresh", VmStatus.RUNNING);
        Vm retired = newVm(group, "retired", VmStatus.STOPPED);
        tagged.setTagsSyncedAt(java.sql.Timestamp.valueOf("2026-01-01 00:00:00"));
        retired.setIsActive(false);
        vmRepository.saveAllAndFlush(List.of(tagged, retired));
        newVm(newGroup(discoveryEnvironment("Other"), "app"), "elsewhere", VmStatus.RUNNING);

        assertThat(vmRepository.findActiveByEnvironmentIdFetchGroupAndEnvironment(env.getEnvironmentId(), true))
                .extracting(Vm::getVmId).containsExactly(fresh.getVmId());
        assertThat(vmRepository.findActiveByEnvironmentIdFetchGroupAndEnvironment(env.getEnvironmentId(), false))
                .extracting(Vm::getVmId).containsExactlyInAnyOrder(tagged.getVmId(), fresh.getVmId());
    }

    @Test
    void aFailedAwsCallFlagsNothing() {
        Environment env = discoveryEnvironment("Throttled");
        Vm present = newVm(discoveryGroup(env), "present", VmStatus.RUNNING);
        awsReturnsNothingByDefault();
        when(awsCloudProviderService.discoverTaggedInstances("ap-south-1", "tcg:environment", env.getName()))
                .thenThrow(new DiscoveryFailedException("EC2 discovery failed in ap-south-1", new RuntimeException("Throttling")));

        discoveryService.discoverAndRegisterVms();

        assertThat(vmRepository.findById(present.getVmId()).orElseThrow().getStateDriftDetected()).isNotEqualTo(Boolean.TRUE);
    }
}
