package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /users/{id}: users read their own record (matched on the app user id, not the Entra oid),
 * admins read anyone's, and the 403 text claims no particular role (E03-T11).
 */
class UserControllerSecurityTest extends SecuredWebTestBase {

    @Test
    void userCanReadTheirOwnRecord() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + operator.getUserId()).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(operator.getUserId()));
    }

    @Test
    void userCannotReadSomeoneElsesRecord() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/users/" + viewer.getUserId()).with(asUser())), 403)
                .andExpect(jsonPath("$.message").value("You do not have permission to perform this action."))
                .andExpect(jsonPath("$.message").value(not(containsString("Required role"))));
    }

    @Test
    void adminCanReadAnyRecord() throws Exception {
        mockMvc.perform(get("/api/v1/users/" + viewer.getUserId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(viewer.getUserId()));
    }

    @Test
    void adminOnlyEndpointDoesNotClaimEnvAdminWouldDo() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/users").with(asEnvAdmin())), 403)
                .andExpect(jsonPath("$.message").value(not(containsString("ENV_ADMIN"))));
    }

    @Test
    void adminListsInactiveUsersOnlyWhenAsked() throws Exception {
        // E04-T09 (M21): User Management loads ?includeInactive=true so Reactivate is reachable.
        viewer.setIsActive(false);
        userRepository.saveAndFlush(viewer);
        String inactive = "$[?(@.userId == '" + viewer.getUserId() + "')]";

        mockMvc.perform(get("/api/v1/users").param("includeInactive", "true").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(inactive + ".active", contains(false)));
        mockMvc.perform(get("/api/v1/users").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(inactive).isEmpty());
    }

    @Test
    void nonAdminCannotListUsers() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/users").param("includeInactive", "true").with(asUser())), 403);
    }
}
