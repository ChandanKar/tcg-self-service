package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Applying a rightsizing recommendation is ADMIN-only and validates the instance type before
 * anything else (E08-T05, M11). Production (Entra ID) chain.
 */
class CostManagementControllerSecurityTest extends SecuredWebTestBase {

    private static final String APPLY = "/api/v1/cost-management/rightsizing/apply";

    @Test
    void aUserCannotApplyRightsizing() throws Exception {
        expectError(mockMvc.perform(post(APPLY).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"vmId\":\"vm-1\",\"targetInstanceType\":\"t3.large\"}")), 403);
    }

    @Test
    void anAdminWithAMalformedInstanceTypeGets400() throws Exception {
        expectError(mockMvc.perform(post(APPLY).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"vmId\":\"vm-1\",\"targetInstanceType\":\"p4d.24xlarge; rm -rf\"}")), 400);
        expectError(mockMvc.perform(post(APPLY).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"vmId\":\"vm-1\",\"targetInstanceType\":\"T3.LARGE\"}")), 400);
    }

    // ---- Bounded parameters, clamped pages, no dead endpoint (E08-T08) ----

    @Test
    void outOfRangeDaysAreRejected() throws Exception {
        expectError(mockMvc.perform(post("/api/v1/cost-management/actuals/backfill?days=500").with(asAdmin())), 400);
        expectError(mockMvc.perform(post("/api/v1/cost-management/snapshots/backfill?days=0").with(asAdmin())), 400);
        expectError(mockMvc.perform(get("/api/v1/cost-management/forecast?historyDays=7").with(asAdmin())), 400);
        expectError(mockMvc.perform(get("/api/v1/cost-management/forecast?forecastDays=365").with(asAdmin())), 400);
        expectError(mockMvc.perform(get("/api/v1/cost-management/spend-trend?days=1000").with(asAdmin())), 400);
        expectError(mockMvc.perform(get("/api/v1/cost-management/reconciliation?days=-1").with(asAdmin())), 400);
    }

    @Test
    void aHugePageSizeIsClampedTo100() throws Exception {
        mockMvc.perform(get("/api/v1/cost-management/vm-detail?size=100000").with(asAdmin()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.page.size").value(100));
    }

    @Test
    void spendByTeamIsGone() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/cost-management/spend-by-team").with(asAdmin())), 404);
    }
}
