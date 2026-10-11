package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who can read savings (E17-T06): fleet reports are admin-only; an environment's for anyone who sees it. */
class SavingsControllerSecurityTest extends SecuredWebTestBase {

    private Environment envA;
    private Environment envB;

    @BeforeEach
    void setUpEnvironments() {
        envA = newEnvironment("Savings A");
        envB = newEnvironment("Savings B");
        grantEnv(viewer, envA.getEnvironmentId(), AccessLevel.VIEWER);
    }

    @Test
    void fleetReportsAreForAdminsOnly() throws Exception {
        for (String url : new String[]{"/api/v1/savings/summary", "/api/v1/savings/by-cause",
                "/api/v1/savings/by-environment", "/api/v1/savings/by-team", "/api/v1/savings/efficiency/trend"}) {
            mockMvc.perform(get(url).with(asAdmin())).andExpect(status().isOk());
            mockMvc.perform(get(url).with(asEnvAdmin())).andExpect(status().isForbidden());
            mockMvc.perform(get(url).with(asUser())).andExpect(status().isForbidden());
            mockMvc.perform(get(url).with(asViewer())).andExpect(status().isForbidden());
            mockMvc.perform(get(url)).andExpect(status().is(org.hamcrest.Matchers.anyOf(
                    org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(302), org.hamcrest.Matchers.is(403))));
        }
        mockMvc.perform(get("/api/v1/savings/export?month=2026-09").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("savings-ledger-2026-09.xlsx")));
        mockMvc.perform(post("/api/v1/savings/backfill?days=2").with(asUser())).andExpect(status().isForbidden());
    }

    @Test
    void anEnvironmentsSavingsAreForThoseWhoCanSeeIt() throws Exception {
        mockMvc.perform(get("/api/v1/environments/" + envA.getEnvironmentId() + "/savings?month=2026-09").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environmentId").value(envA.getEnvironmentId()))
                .andExpect(jsonPath("$.realisedNet").value(0));
        mockMvc.perform(get("/api/v1/environments/" + envB.getEnvironmentId() + "/savings").with(asViewer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void badParametersAreRejected() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/savings/summary?month=September").with(asAdmin())), 400);
        mockMvc.perform(post("/api/v1/savings/backfill?days=0").with(asAdmin())).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/savings/backfill?days=91").with(asAdmin())).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/savings/backfill?days=2").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(2));
    }
}
