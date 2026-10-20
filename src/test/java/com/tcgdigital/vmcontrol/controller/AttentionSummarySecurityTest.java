package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** U05-T04: the "needs attention" summary is for administrators only. */
class AttentionSummarySecurityTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/dashboard/attention";

    @Test
    void anAdministratorGetsEveryBlock() throws Exception {
        mockMvc.perform(get(URL).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessRequests.count").isNumber())
                .andExpect(jsonPath("$.approvals.count").isNumber())
                .andExpect(jsonPath("$.failedOperations.count24h").isNumber())
                .andExpect(jsonPath("$.vmsToReview.drift").isNumber())
                .andExpect(jsonPath("$.budgets.nearLimit").isNumber())
                .andExpect(jsonPath("$.lateJobs.count").isNumber())
                .andExpect(jsonPath("$.health.jobsEnabled").isNumber())
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    void everyoneElseIsRefused() throws Exception {
        expectError(mockMvc.perform(get(URL).with(asEnvAdmin())), 403);
        expectError(mockMvc.perform(get(URL).with(asUser())), 403);
        expectError(mockMvc.perform(get(URL).with(asViewer())), 403);
    }
}
