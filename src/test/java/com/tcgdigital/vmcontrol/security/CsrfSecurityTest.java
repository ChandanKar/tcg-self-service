package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CSRF is enforced for the API under the production chain (E03-T10, M22).
 */
class CsrfSecurityTest extends SecuredWebTestBase {

    private static final String BODY = "{\"name\":\"csrf-env\",\"displayName\":\"CSRF Env\",\"cloudProvider\":\"AWS\"}";

    @Test
    void mutationWithoutTokenIsRefused() throws Exception {
        expectError(mockMvc.perform(post("/api/v1/environments").with(asWithoutCsrf(admin))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)), 403);
    }

    @Test
    void mutationWithTokenIsAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/environments").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void readsDoNotNeedAToken() throws Exception {
        mockMvc.perform(get("/api/v1/environments").with(asWithoutCsrf(viewer))).andExpect(status().isOk());
    }

    @Test
    void cspReportsAreExemptBecauseBrowsersCannotSendTheHeader() throws Exception {
        mockMvc.perform(post(SecurityHeaders.REPORT_PATH)
                        .contentType(MediaType.valueOf("application/csp-report"))
                        .content("{\"csp-report\":{\"violated-directive\":\"script-src-elem\"}}"))
                .andExpect(status().isNoContent());
    }
}
