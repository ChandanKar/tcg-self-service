package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.service.StateSyncService;
import com.tcgdigital.vmcontrol.service.VmDiscoveryService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.ec2.model.Instance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deleting a VM is a soft delete (E09-T09, M35): the row and its history stay, the delete is
 * audited with its actor, discovery does not bring the instance back, and reactivating undoes it.
 */
class VmSoftDeleteControllerTest extends SecuredWebTestBase {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StateSyncService stateSyncService;
    @Autowired private VmDiscoveryService discoveryService;

    private Environment env;
    private VmGroup group;
    private Vm vm;

    @BeforeEach
    void seed() {
        env = newEnvironment("SoftDelete");
        env.setServiceType("EC2");
        env.setMetadata("{\"region\":\"ap-south-1\"}");
        environmentRepository.saveAndFlush(env);
        group = newGroup(env, "app");
        vm = newVm(group, "app-1", VmStatus.RUNNING);
        stateSyncService.recordStateChange(vmRepository.getReferenceById(vm.getVmId()),
                VmStatus.STOPPED, VmStatus.RUNNING, "operation", null, null, "started");
        when(cloudProviderFactory.getService(CloudProvider.AWS)).thenReturn(awsCloudProviderService);
        when(awsCloudProviderService.isAvailable()).thenReturn(true);
    }

    private String vmUrl() {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/vms/" + vm.getVmId();
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void deleteKeepsTheRowAndItsHistoryAndRecordsWhoDidIt() throws Exception {
        mockMvc.perform(delete(vmUrl()).with(asEnvAdmin())).andExpect(status().isNoContent());

        Vm after = vmRepository.findById(vm.getVmId()).orElseThrow();
        assertThat(after.getIsActive()).isFalse();
        assertThat(after.getDiscoveryIgnored()).isTrue();
        assertThat(after.getDeletedBy()).isEqualTo(envAdmin.getUserId());
        assertThat(after.getDeletedAt()).isNotNull();
        assertThat(count("SELECT COUNT(*) FROM vm_state_history WHERE vm_id = ?", vm.getVmId())).isEqualTo(1);
        awaitAsync(() -> assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action_type = 'VM_DELETED' " +
                "AND target_id = ? AND user_id = ?", vm.getVmId(), envAdmin.getUserId())).isEqualTo(1));
    }

    @Test
    void discoveryDoesNotBringADeletedInstanceBack() throws Exception {
        VmGroup discovered = newGroup(env, "discovered");
        discovered.setName("discovered");
        vmGroupRepository.saveAndFlush(discovered);
        mockMvc.perform(delete(vmUrl()).with(asAdmin())).andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE vm SET status = 'NOT_FOUND' WHERE vm_id = ?", vm.getVmId());
        when(awsCloudProviderService.discoverTaggedInstances(eq("ap-south-1"), anyString(), anyString())).thenReturn(List.of());
        when(awsCloudProviderService.discoverTaggedInstances("ap-south-1", "tcg:environment", env.getName()))
                .thenReturn(List.of(Instance.builder().instanceId(vm.getProviderVmId()).build()));

        discoveryService.discoverAndRegisterVms();

        Vm after = vmRepository.findById(vm.getVmId()).orElseThrow();
        assertThat(after.getIsActive()).isFalse();
        assertThat(after.getDiscoveryIgnored()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM vm WHERE provider_vm_id = ?", vm.getProviderVmId())).isEqualTo(1);
    }

    @Test
    void reactivatingUndoesTheDelete() throws Exception {
        mockMvc.perform(delete(vmUrl()).with(asAdmin())).andExpect(status().isNoContent());
        when(awsCloudProviderService.getVmStatus(anyString(), anyString())).thenReturn(VmStatus.RUNNING);

        mockMvc.perform(post(vmUrl() + "/reactivate").with(asAdmin())).andExpect(status().isOk());

        Vm after = vmRepository.findById(vm.getVmId()).orElseThrow();
        assertThat(after.getIsActive()).isTrue();
        assertThat(after.getDiscoveryIgnored()).isFalse();
        assertThat(after.getDeletedAt()).isNull();
        assertThat(after.getDeletedBy()).isNull();
    }

    @Test
    void aVmOthersDependOnCannotBeDeleted() throws Exception {
        Vm dependent = newVm(group, "app-2", VmStatus.RUNNING);
        dependent.setDependencies(List.of(vm.getVmId()));
        vmRepository.saveAndFlush(dependent);

        expectError(mockMvc.perform(delete(vmUrl()).with(asAdmin())), 400);
        assertThat(vmRepository.findById(vm.getVmId()).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    void usersCannotDelete() throws Exception {
        mockMvc.perform(delete(vmUrl()).with(asUser())).andExpect(status().isForbidden());
    }
}
