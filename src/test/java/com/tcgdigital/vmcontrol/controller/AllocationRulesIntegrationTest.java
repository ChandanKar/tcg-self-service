package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.service.AllocationValidator;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Required allocation fields enforced (E23-T04): creating an environment without a team and
 * registering a VM into an environment without a required metadata key are 400 with the missing
 * fields named; the gaps list shows such environments to admins.
 */
class AllocationRulesIntegrationTest extends SecuredWebTestBase {

    @Autowired private AllocationValidator validator;

    private void rules(boolean requireTeam, String keys) {
        Object target = AopTestUtils.getTargetObject(validator);
        ReflectionTestUtils.setField(target, "requireTeam", requireTeam);
        ReflectionTestUtils.setField(target, "requiredMetadataKeys", keys);
    }

    @AfterEach
    void off() {
        rules(false, "");
    }

    @Test
    void offByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/environments/allocation-rules").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireTeam").value(false));
        mockMvc.perform(post("/api/v1/environments").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"free-" + System.nanoTime() + "\",\"displayName\":\"Free\"}")).andExpect(status().isCreated());
    }

    @Test
    void aTeamIsRequiredOnCreate() throws Exception {
        rules(true, "");
        expectError(mockMvc.perform(post("/api/v1/environments").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"no-team-" + System.nanoTime() + "\",\"displayName\":\"No team\"}")), 400)
                .andExpect(jsonPath("$.message").value("Missing allocation fields: team"));
    }

    @Test
    void aVmCannotBeRegisteredIntoAnEnvironmentMissingAKeyAndTheGapIsListed() throws Exception {
        Environment env = newEnvironment("Unallocated");
        VmGroup group = newGroup(env, "app");
        rules(false, "costCenter");

        expectError(mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/vms").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":\"" + group.getGroupId() + "\",\"name\":\"web\",\"displayName\":\"Web\",\"provider\":\"AWS\","
                        + "\"providerVmId\":\"i-" + System.nanoTime() + "\",\"region\":\"ap-south-1\",\"sequencePosition\":1}")), 400)
                .andExpect(jsonPath("$.message").value("Missing allocation fields: costCenter"));
        mockMvc.perform(get("/api/v1/environments/allocation-gaps").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.environmentId == '" + env.getEnvironmentId() + "')].missing[0]").value("costCenter"));
        mockMvc.perform(get("/api/v1/environments/allocation-gaps").with(asUser())).andExpect(status().isForbidden());
    }
}
