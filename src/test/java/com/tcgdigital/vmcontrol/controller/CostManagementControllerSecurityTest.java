package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

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
}
