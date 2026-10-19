package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** U02-T01: GET /api/v1/users/me/capabilities tells the app the person's role and the feature switches. */
class CapabilitiesControllerTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/users/me/capabilities";

    @Autowired private SettingsService settings;

    @AfterEach
    void resetSwitch() {
        settings.reset("provisioning.enabled", admin.getUserId());
    }

    @Test
    void rolesAreReportedPerPerson() throws Exception {
        mockMvc.perform(get(URL).with(asAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true)).andExpect(jsonPath("$.envAdmin").value(true));
        mockMvc.perform(get(URL).with(asEnvAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(false)).andExpect(jsonPath("$.envAdmin").value(true));
        mockMvc.perform(get(URL).with(asUser())).andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(false)).andExpect(jsonPath("$.envAdmin").value(false));
    }

    @Test
    void switchesFollowTheSettingsIncludingRuntimeOverrides() throws Exception {
        mockMvc.perform(get(URL).with(asViewer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.features.templates").value(false))
                .andExpect(jsonPath("$.features.webhooks").value(false))
                .andExpect(jsonPath("$.features.leases").exists());

        settings.set("provisioning.enabled", "true", admin.getUserId());

        mockMvc.perform(get(URL).with(asViewer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.features.templates").value(true));
    }

    @Test
    void signedOutPeopleGetNothing() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().is(org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.is(401), org.hamcrest.Matchers.is(302), org.hamcrest.Matchers.is(403))));
    }
}
