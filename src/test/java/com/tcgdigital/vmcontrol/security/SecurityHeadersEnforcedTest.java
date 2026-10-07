package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With security.csp.report-only=false the policy is sent as the enforcing header. */
@TestPropertySource(properties = "security.csp.report-only=false")
class SecurityHeadersEnforcedTest extends SecuredWebTestBase {

    @Test
    void policyIsEnforcedWhenReportOnlyIsOff() throws Exception {
        SecurityHeadersTest.expectHardening(
                        mockMvc.perform(get("/login.html")).andExpect(status().isOk()), SecurityHeadersTest.CSP)
                .andExpect(header().doesNotExist(SecurityHeadersTest.CSP_REPORT_ONLY));
    }
}
