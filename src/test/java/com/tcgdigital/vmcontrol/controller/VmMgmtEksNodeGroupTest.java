package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U07-T08: the environment's VM listing carries each EKS node group's nodes (instance type, size
 * when started, nodes now, capacity type); EC2 VMs carry none.
 */
@AutoConfigureMockMvc
class VmMgmtEksNodeGroupTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private Vm nodeGroup(VmGroup group, String name, VmStatus status, String metadata, PurchaseOption option) {
        Vm vm = newVm(group, name, status);
        vm.setProvider(CloudProvider.AWS_EKS);
        vm.setProviderVmId("cluster-a/" + vm.getName());
        vm.setMetadata(metadata);
        vm.setPurchaseOption(option);
        return vmRepository.saveAndFlush(vm);
    }

    @Test
    void nodeGroupsCarryTheirNodesAndEc2VmsDoNot() throws Exception {
        Environment env = newEnvironment("EKS listing");
        VmGroup system = newGroup(env, "system");
        VmGroup batch = newGroup(env, "batch");
        Vm running = nodeGroup(system, "system-nodes", VmStatus.RUNNING,
                "{\"minSize\":1,\"desiredSize\":3,\"instanceType\":\"m6i.large\"}", PurchaseOption.ON_DEMAND);
        Vm scaledDown = nodeGroup(batch, "batch-nodes", VmStatus.STOPPED,
                "{\"minSize\":1,\"desiredSize\":2,\"instanceType\":\"c6i.xlarge\"}", PurchaseOption.SPOT);
        Vm ec2 = newVm(batch, "plain-vm", VmStatus.RUNNING);

        String vm = "$[*].vms[?(@.vmId == '%s')].eksNodeGroup";
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/vms").header("X-User-Id", "admin-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(vm.formatted(running.getVmId()) + ".currentNodes", contains(3)))
                .andExpect(jsonPath(vm.formatted(running.getVmId()) + ".desiredWhenRunning", contains(3)))
                .andExpect(jsonPath(vm.formatted(running.getVmId()) + ".instanceType", contains("m6i.large")))
                .andExpect(jsonPath(vm.formatted(scaledDown.getVmId()) + ".currentNodes", contains(0)))
                .andExpect(jsonPath(vm.formatted(scaledDown.getVmId()) + ".desiredWhenRunning", contains(2)))
                .andExpect(jsonPath(vm.formatted(scaledDown.getVmId()) + ".capacityType", contains("SPOT")))
                .andExpect(jsonPath(vm.formatted(ec2.getVmId())).isEmpty());
    }
}
