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
    void anAdminCanDoBoth() throws Exception {
        Environment env = newEnvironment("Caps admin");

        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerCanOperate").value(true))
                .andExpect(jsonPath("$.viewerCanAdminister").value(true));
    }
}
