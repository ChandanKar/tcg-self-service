package com.tcgdigital.vmcontrol.support;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves that method security and principal resolution work in the secured test base. */
class SecuredWebTestBaseSmokeTest extends SecuredWebTestBase {

    @Test
    void anonymousCallToApiReturns401Json() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/users")), 401);
    }

    @Test
    void adminOnlyEndpointRejectsPlainUser() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/users").with(asUser())), 403);
    }

    @Test
    void adminOnlyEndpointAcceptsAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/users").with(asAdmin())).andExpect(status().isOk());
    }

    @Test
    void signedInViewerCanListEnvironments() throws Exception {
        mockMvc.perform(get("/api/v1/environments").with(asViewer())).andExpect(status().isOk());
    }

    @Test
    void currentUserIsResolvedFromTheTokenOid() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("operator@secured.test"));
    }
}
