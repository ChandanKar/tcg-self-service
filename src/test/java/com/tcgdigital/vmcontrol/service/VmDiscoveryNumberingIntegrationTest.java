package com.tcgdigital.vmcontrol.service;

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
 * Against MySQL's unique index on (group_id, sequence_position): with an inactive VM in the
 * discovery group, a newly discovered instance is registered instead of colliding (E09-T01, H8).
 */
class VmDiscoveryNumberingIntegrationTest extends AbstractIntegrationTest {

    @Autowired private VmDiscoveryService discoveryService;

    @Test
    void anInactiveVmInTheDiscoveryGroupDoesNotBlockNewRegistrations() {
        Environment env = newEnvironment("Disc");
        env.setServiceType("EC2");
        env.setMetadata("{\"region\":\"ap-south-1\"}");
        environmentRepository.saveAndFlush(env);
        VmGroup group = newGroup(env, "discovered");
        group.setName("discovered");
        vmGroupRepository.saveAndFlush(group);
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
        when(awsCloudProviderService.isAvailable()).thenReturn(true);
        when(awsCloudProviderService.discoverTaggedInstances(eq("ap-south-1"), anyString(), anyString())).thenReturn(List.of());
        when(awsCloudProviderService.discoverTaggedInstances("ap-south-1", "tcg:environment", env.getName()))
                .thenReturn(List.of(live, Instance.builder().instanceId(one.getProviderVmId()).build(),
                        Instance.builder().instanceId(three.getProviderVmId()).build()));

        discoveryService.discoverAndRegisterVms();

        Vm registered = vmRepository.findByProviderAndProviderVmId(one.getProvider(), live.instanceId()).orElseThrow();
        assertThat(registered.getGroup().getGroupId()).isEqualTo(group.getGroupId());
        assertThat(registered.getSequencePosition()).isEqualTo(three.getSequencePosition() + 1);
    }
}
