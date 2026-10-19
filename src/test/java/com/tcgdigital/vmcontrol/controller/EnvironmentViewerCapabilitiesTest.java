package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/environments/{id} tells the page what the signed-in viewer may do, so it shows
 * Start/Stop only to people who can use them.
 */
class EnvironmentViewerCapabilitiesTest extends SecuredWebTestBase {

    @Test
    void aViewerCanNeitherOperateNorAdminister() throws Exception {
        Environment env = newEnvironment("Caps viewer");
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);

        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId()).with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerCanOperate").value(false))
                .andExpect(jsonPath("$.viewerCanAdminister").value(false));
    }

    @Test
    void aUserGrantOperatesButDoesNotAdminister() throws Exception {
        Environment env = newEnvironment("Caps user");
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);

        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId()).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerCanOperate").value(true))
                .andExpect(jsonPath("$.viewerCanAdminister").value(false));
    }

    @Test
    void theListAndThePageCarryTheFlagsToo() throws Exception {
        Environment env = newEnvironment("Caps list");
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);

        mockMvc.perform(get("/api/v1/environments").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.environmentId=='" + env.getEnvironmentId() + "')].viewerCanOperate").value(false))
                .andExpect(jsonPath("$[?(@.environmentId=='" + env.getEnvironmentId() + "')].viewerCanManageAccess").value(false));
        mockMvc.perform(get("/api/v1/environments/page").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].viewerCanOperate").value(true))
                .andExpect(jsonPath("$.content[0].viewerCanAdminister").value(true))
                .andExpect(jsonPath("$.content[0].viewerCanManageAccess").value(true));
    }

    @Test
    void anOperatorOfOneGroupOperatesThatGroupOnly() throws Exception {
        Environment env = newEnvironment("Caps group");
        com.tcgdigital.vmcontrol.model.VmGroup mine = newGroup(env, "mine");
        com.tcgdigital.vmcontrol.model.VmGroup theirs = newGroup(env, "theirs");
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.VIEWER);
        grantGroup(operator, mine.getGroupId(), AccessLevel.USER);

        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/vms").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.group.groupId=='" + mine.getGroupId() + "')].viewerCanOperate").value(true))
                .andExpect(jsonPath("$[?(@.group.groupId=='" + theirs.getGroupId() + "')].viewerCanOperate").value(false));
    }

    @Test
    void anAdminCanDoBoth() throws Exception {
        Environment env = newEnvironment("Caps admin");

        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerCanOperate").value(true))
                .andExpect(jsonPath("$.viewerCanAdminister").value(true));
    }
}
