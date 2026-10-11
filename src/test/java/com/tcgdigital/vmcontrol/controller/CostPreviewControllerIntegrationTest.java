package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET .../operations/cost-preview (E25-T01): view rights, scoping and the response shape. */
class CostPreviewControllerIntegrationTest extends SecuredWebTestBase {

    @Test
    void viewersSeeItOthersDoNot() throws Exception {
        Environment env = newEnvironment("Priced");
        VmGroup group = newGroup(env, "web");
        newVm(group, "web-1", com.tcgdigital.vmcontrol.model.VmStatus.STOPPED);
        String url = "/api/v1/environments/" + env.getEnvironmentId() + "/operations/cost-preview";

        mockMvc.perform(get(url).with(asViewer())).andExpect(status().isForbidden());
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
        mockMvc.perform(get(url).param("groupId", group.getGroupId()).param("hours", "10000").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours").value(720))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.vms").isArray())
                .andExpect(jsonPath("$.estimatedTotal").isNumber());

        Environment other = newEnvironment("Elsewhere");
        VmGroup foreign = newGroup(other, "db");
        expectError(mockMvc.perform(get(url).param("groupId", foreign.getGroupId()).with(asAdmin())), 404);
    }
}
