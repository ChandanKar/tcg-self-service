package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security headers under the production (Entra ID) chain in the default rollout mode
 * (report-only). {@link SecurityHeadersEnforcedTest} covers the enforced header.
 */
class SecurityHeadersTest extends SecuredWebTestBase {

    static final String CSP = "Content-Security-Policy";
    static final String CSP_REPORT_ONLY = "Content-Security-Policy-Report-Only";

    @Test
    void loginPageGetsReportOnlyPolicyAndFrameDeny() throws Exception {
        expectHardening(mockMvc.perform(get("/login.html")).andExpect(status().isOk()), CSP_REPORT_ONLY)
                .andExpect(header().doesNotExist(CSP));
    }

    @Test
    void apiResponsesGetTheSameHeaders() throws Exception {
        expectHardening(mockMvc.perform(get("/api/v1/environments").with(asViewer())).andExpect(status().isOk()),
                CSP_REPORT_ONLY);
    }

    @Test
    void scriptsAreRestrictedToOwnOrigin() throws Exception {
        mockMvc.perform(get("/login.html"))
                .andExpect(header().string(CSP_REPORT_ONLY, allOf(
                        containsString("script-src 'self'"),
                        containsString("object-src 'none'"),
                        containsString("frame-ancestors 'none'"),
                        not(containsString("unsafe-eval")))))
                .andExpect(header().string(CSP_REPORT_ONLY, not(containsString("script-src 'self' 'unsafe-inline'"))));
    }

    @Test
    void swaggerUiIsExcludedFromThePolicy() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(header().doesNotExist(CSP_REPORT_ONLY))
                .andExpect(header().doesNotExist(CSP));
    }

    @Test
    void h2ConsoleIsNoLongerOpen() throws Exception {
        mockMvc.perform(get("/h2-console/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login")));
    }

    @Test
    void violationReportsAreAcceptedAnonymously() throws Exception {
        mockMvc.perform(post(SecurityHeaders.REPORT_PATH)
                        .contentType(MediaType.valueOf("application/csp-report"))
                        .content("{\"csp-report\":{\"violated-directive\":\"script-src-elem\",\"blocked-uri\":\"inline\"}}"))
                .andExpect(status().isNoContent());
    }

    static ResultActions expectHardening(ResultActions result, String cspHeader) throws Exception {
        return result
                .andExpect(header().string(cspHeader, containsString("default-src 'self'")))
                .andExpect(header().string(cspHeader, containsString("report-uri " + SecurityHeaders.REPORT_PATH)))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }
}
