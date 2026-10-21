package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** U14-T02: System health's scheduled-jobs list is for administrators only. */
class SchedulerHealthSecurityTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/monitoring/scheduler-health";

    @Test
    void anAdministratorGetsEveryJobWithItsCadenceAndStatus() throws Exception {
        mockMvc.perform(get(URL).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(35))
                .andExpect(jsonPath("$[0].name").isString())
                .andExpect(jsonPath("$[0].cadence").isString())
                .andExpect(jsonPath("$[0].status").isString())
                .andExpect(jsonPath("$[?(@.jobName == 'cost_snapshot')].cadence").value(org.hamcrest.Matchers.contains("Daily 03:00")));
    }

    @Test
    void everyoneElseIsRefused() throws Exception {
        expectError(mockMvc.perform(get(URL).with(asEnvAdmin())), 403);
        expectError(mockMvc.perform(get(URL).with(asUser())), 403);
        expectError(mockMvc.perform(get(URL).with(asViewer())), 403);
    }
}
