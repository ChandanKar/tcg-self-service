package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U08-T06: a VM's advice (cost this month, purchase type, rightsizing) for everyone who can view
 * the VM, with the same access checks as the VM itself and no instance ID in the payload.
 */
@AutoConfigureMockMvc
class VmAdviceControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private String url(Environment env, Vm vm) {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/vms/" + vm.getVmId() + "/advice";
    }

    @Test
    void aViewerGetsTheVmsAdviceWithoutItsInstanceId() throws Exception {
        Environment env = newEnvironment("Advice");
        Vm vm = newVm(newGroup(env, "app"), "pay-api-01", VmStatus.RUNNING);
        vm.setPurchaseOption(PurchaseOption.SPOT);
        vmRepository.saveAndFlush(vm);
        User viewer = newUser("advice-viewer-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@test.com", false, false);
        grant(viewer, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.VIEWER);

        String body = mockMvc.perform(get(url(env, vm)).header("X-User-Id", viewer.getUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchaseOption").value("SPOT"))
                .andExpect(jsonPath("$.rightsizing").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(vm.getProviderVmId());
    }

    @Test
    void someoneWithoutAccessIsRefused() throws Exception {
        Environment env = newEnvironment("Advice closed");
        Vm vm = newVm(newGroup(env, "app"), "closed-01", VmStatus.STOPPED);
        User stranger = newUser("advice-stranger-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@test.com", false, false);

        mockMvc.perform(get(url(env, vm)).header("X-User-Id", stranger.getUserId()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aVmOfAnotherEnvironmentIsNotFoundThroughThisOne() throws Exception {
        Environment env = newEnvironment("Advice A");
        Environment other = newEnvironment("Advice B");
        Vm vm = newVm(newGroup(other, "app"), "elsewhere-01", VmStatus.STOPPED);

        mockMvc.perform(get(url(env, vm)).header("X-User-Id", "admin-001"))
                .andExpect(status().isNotFound());
    }
}
