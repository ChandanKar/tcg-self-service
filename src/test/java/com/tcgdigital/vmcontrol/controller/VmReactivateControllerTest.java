package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/environments/{envId}/vms/{vmId}/reactivate brings back a VM that state sync
 * deactivated, once the instance exists again (E09-T06, M7).
 */
class VmReactivateControllerTest extends SecuredWebTestBase {

    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private Vm vm;

    @BeforeEach
    void seed() {
        env = newEnvironment("Reactivate");
        vm = newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        jdbcTemplate.update("UPDATE vm SET is_active = FALSE, status = 'NOT_FOUND', not_found_count = 3, " +
                "state_drift_detected = TRUE WHERE vm_id = ?", vm.getVmId());
        when(cloudProviderFactory.getService(CloudProvider.AWS)).thenReturn(awsCloudProviderService);
        when(awsCloudProviderService.isAvailable()).thenReturn(true);
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.STOPPED);
    }

    private String url(String environmentId) {
        return "/api/v1/environments/" + environmentId + "/vms/" + vm.getVmId() + "/reactivate";
    }

    private int audits() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action_type = 'VM_REACTIVATED' " +
                "AND target_id = ?", Integer.class, vm.getVmId());
        return n == null ? 0 : n;
    }

    @Test
    void anAdminReactivatesWithTheCloudStatus() throws Exception {
        mockMvc.perform(post(url(env.getEnvironmentId())).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vmId").value(vm.getVmId()))
                .andExpect(jsonPath("$.status").value("STOPPED"));

        Vm after = vmRepository.findById(vm.getVmId()).orElseThrow();
        assertThat(after.getIsActive()).isTrue();
        assertThat(after.getStatus()).isEqualTo(VmStatus.STOPPED);
        assertThat(after.getNotFoundCount()).isZero();
        assertThat(after.getStateDriftDetected()).isFalse();
        awaitAsync(() -> assertThat(audits()).isEqualTo(1)); // written on the async executor
    }

    @Test
    void anEnvAdminCanReactivate() throws Exception {
        mockMvc.perform(post(url(env.getEnvironmentId())).with(asEnvAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void usersAndViewersCannot() throws Exception {
        mockMvc.perform(post(url(env.getEnvironmentId())).with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(post(url(env.getEnvironmentId())).with(asViewer())).andExpect(status().isForbidden());
        assertThat(vmRepository.findById(vm.getVmId()).orElseThrow().getIsActive()).isFalse();
    }

    @Test
    void theVmMustBelongToTheEnvironmentInThePath() throws Exception {
        Environment other = newEnvironment("Elsewhere");
        expectError(mockMvc.perform(post(url(other.getEnvironmentId())).with(asAdmin())), 404);
        assertThat(vmRepository.findById(vm.getVmId()).orElseThrow().getIsActive()).isFalse();
    }

    @Test
    void anInstanceThatIsStillMissingIsRefused() throws Exception {
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.NOT_FOUND);

        expectError(mockMvc.perform(post(url(env.getEnvironmentId())).with(asAdmin())), 400)
                .andExpect(jsonPath("$.message").value("Instance not found in ap-south-1 - fix the region first"));
        assertThat(vmRepository.findById(vm.getVmId()).orElseThrow().getIsActive()).isFalse();
        assertThat(audits()).isZero();
    }

    @Test
    void anActiveVmIsRefused() throws Exception {
        jdbcTemplate.update("UPDATE vm SET is_active = TRUE, status = 'RUNNING' WHERE vm_id = ?", vm.getVmId());

        expectError(mockMvc.perform(post(url(env.getEnvironmentId())).with(asAdmin())), 400);
    }
}
