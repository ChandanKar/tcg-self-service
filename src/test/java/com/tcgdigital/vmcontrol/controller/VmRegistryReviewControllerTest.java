package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registry review endpoints (E09-T08, M34): review lists and counts, moving a VM out of
 * Auto-Discovered, names checked as stored, and clamped paging.
 */
class VmRegistryReviewControllerTest extends SecuredWebTestBase {

    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private VmGroup discovered;
    private VmGroup web;
    private Vm pending;

    @BeforeEach
    void seed() {
        env = newEnvironment("Registry");
        discovered = newGroup(env, "discovered");
        web = newGroup(env, "web");
        for (int i = 1; i <= 3; i++) {
            Vm drifted = newVm(web, "drift-" + i, VmStatus.RUNNING);
            jdbcTemplate.update("UPDATE vm SET state_drift_detected = TRUE WHERE vm_id = ?", drifted.getVmId());
        }
        pending = newVm(discovered, "found-1", VmStatus.UNKNOWN);
        Vm pending2 = newVm(discovered, "found-2", VmStatus.UNKNOWN);
        jdbcTemplate.update("UPDATE vm SET discovery_pending = TRUE WHERE vm_id IN (?, ?)", pending.getVmId(), pending2.getVmId());
        Vm retired = newVm(web, "retired", VmStatus.NOT_FOUND);
        jdbcTemplate.update("UPDATE vm SET is_active = FALSE WHERE vm_id = ?", retired.getVmId());
    }

    private String base() {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/vms";
    }

    @Test
    void anEnvAdminListsDriftedVmsAndTheCounts() throws Exception {
        mockMvc.perform(get(base() + "/review").param("state", "DRIFT").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.page.totalElements").value(3));
        mockMvc.perform(get(base() + "/review").param("state", "pending").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
        mockMvc.perform(get(base() + "/review/counts").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drift").value(3))
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.inactive").value(1));
    }

    @Test
    void usersAndViewersCannotReview() throws Exception {
        mockMvc.perform(get(base() + "/review").param("state", "DRIFT").with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(get(base() + "/review").param("state", "DRIFT").with(asViewer())).andExpect(status().isForbidden());
        mockMvc.perform(get(base() + "/review/counts").with(asUser())).andExpect(status().isForbidden());
    }

    @Test
    void anUnknownStateIs400() throws Exception {
        expectError(mockMvc.perform(get(base() + "/review").param("state", "BROKEN").with(asAdmin())), 400);
    }

    @Test
    void aPendingVmIsMovedOutOfAutoDiscovered() throws Exception {
        Integer highest = jdbcTemplate.queryForObject("SELECT MAX(sequence_position) FROM vm WHERE group_id = ?",
                Integer.class, web.getGroupId());

        mockMvc.perform(put(base() + "/" + pending.getVmId() + "/move").with(asEnvAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetGroupId\":\"" + web.getGroupId() + "\"}"))
                .andExpect(status().isOk());

        Vm moved = vmRepository.findById(pending.getVmId()).orElseThrow();
        assertThat(moved.getGroup().getGroupId()).isEqualTo(web.getGroupId());
        assertThat(moved.getSequencePosition()).isEqualTo(highest + 1);
        assertThat(moved.getDiscoveryPending()).isFalse();
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action_type = 'VM_UPDATED' AND target_id = ?",
                Integer.class, pending.getVmId())).isEqualTo(1));
    }

    @Test
    void aMoveToAnotherEnvironmentsGroupIsRefused() throws Exception {
        VmGroup foreign = newGroup(newEnvironment("Foreign"), "web");

        expectError(mockMvc.perform(put(base() + "/" + pending.getVmId() + "/move").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetGroupId\":\"" + foreign.getGroupId() + "\"}")), 400);

        assertThat(vmRepository.findById(pending.getVmId()).orElseThrow().getGroup().getGroupId())
                .isEqualTo(discovered.getGroupId());
    }

    @Test
    void usersCannotMove() throws Exception {
        mockMvc.perform(put(base() + "/" + pending.getVmId() + "/move").with(asUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetGroupId\":\"" + web.getGroupId() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aNameThatNormalisesToAnExistingOneIs400Not500() throws Exception {
        Vm existing = vmRepository.findById(pending.getVmId()).orElseThrow(); // name found-1-xxxx
        String spaced = existing.getName().replace('-', ' ').toUpperCase();

        expectError(mockMvc.perform(post(base()).with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":\"" + discovered.getGroupId() + "\",\"name\":\"" + spaced + "\"," +
                        "\"displayName\":\"Clash\",\"provider\":\"AWS\",\"region\":\"ap-south-1\"," +
                        "\"providerVmId\":\"i-0clash" + System.nanoTime() + "\",\"sequencePosition\":900}")), 400)
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already exists")));
    }

    @Test
    void groupPagingIsClampedAndValidated() throws Exception {
        mockMvc.perform(get(base() + "/" + web.getGroupId() + "/page").param("size", "10000").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
        expectError(mockMvc.perform(get(base() + "/" + web.getGroupId() + "/page").param("page", "-1").with(asAdmin())), 400);
    }
}
