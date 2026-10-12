package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** My notification preferences (E29-T05): every type listed, changes kept, required bells stay on. */
class NotificationPreferenceControllerTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/users/me/notification-preferences";

    @Test
    void aUserReadsAndChangesTheirOwnPreferences() throws Exception {
        mockMvc.perform(get(URL).with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'OPERATION_COMPLETED')].inApp").value(true))
                .andExpect(jsonPath("$[?(@.type == 'LOCK_BROKEN')].inAppLocked").value(true));

        mockMvc.perform(put(URL).with(asViewer()).contentType(MediaType.APPLICATION_JSON).content(
                        "[{\"type\":\"OPERATION_COMPLETED\",\"inApp\":false},{\"type\":\"ACCESS_EXPIRING\",\"email\":false},"
                                + "{\"type\":\"LOCK_BROKEN\",\"inApp\":false}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'OPERATION_COMPLETED')].inApp").value(false))
                .andExpect(jsonPath("$[?(@.type == 'LOCK_BROKEN')].inApp").value(true));

        mockMvc.perform(get(URL).with(asViewer()))
                .andExpect(jsonPath("$[?(@.type == 'OPERATION_COMPLETED')].inApp").value(false))
                .andExpect(jsonPath("$[?(@.type == 'ACCESS_EXPIRING')].email").value(false))
                .andExpect(jsonPath("$[?(@.type == 'ACCESS_EXPIRING')].inApp").value(true));

        // Another user's preferences are untouched.
        mockMvc.perform(get(URL).with(asUser()))
                .andExpect(jsonPath("$[?(@.type == 'OPERATION_COMPLETED')].inApp").value(true));
    }

    @Test
    void anUnknownTypeIsRejected() throws Exception {
        expectError(mockMvc.perform(put(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"type\":\"NOT_A_TYPE\",\"inApp\":false}]")), 400);
    }
}
