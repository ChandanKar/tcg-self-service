package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The settings API (E29-T01): admins only, tier 1 editable with validation, reset, audit. */
class SettingsControllerIntegrationTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/settings";
    private static final String KEY = "notification.email.lock-broken.enabled";

    @Autowired private SettingsService settings;
    @Autowired private AuditLogRepository auditLogs;

    private String body(String value) {
        return "{\"value\":\"" + value + "\"}";
    }

    @Test
    void settingsShowTheirPropertyValueUntilOverriddenThenTheDefaultIsBackAfterReset() throws Exception {
        mockMvc.perform(get(URL).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == '" + KEY + "')].value").value("false"))
                .andExpect(jsonPath("$[?(@.key == '" + KEY + "')].source").value("DEFAULT"))
                .andExpect(jsonPath("$[?(@.key == 'entraid.enabled')].editable").value(false));

        mockMvc.perform(put(URL + "/" + KEY).with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(body("true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("true"))
                .andExpect(jsonPath("$.source").value("OVERRIDE"));
        assertThat(settings.getBoolean(KEY, false)).isTrue();
        awaitAsync(() -> assertThat(auditLogs.findAll()).anyMatch(l -> l.getAction() == AuditAction.SYSTEM_CONFIG_UPDATED
                && l.getDetails() != null && l.getDetails().contains("false -> true")));

        mockMvc.perform(delete(URL + "/" + KEY).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("DEFAULT"));
        assertThat(settings.getBoolean(KEY, false)).isFalse(); // no override: the property or the caller's default
    }

    @Test
    void readOnlyAndInvalidValuesAreRejected() throws Exception {
        expectError(mockMvc.perform(put(URL + "/entraid.enabled").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("false"))), 400);
        expectError(mockMvc.perform(put(URL + "/vm.metrics.idle.cpu-threshold-percent").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(body("500"))), 400);
        expectError(mockMvc.perform(put(URL + "/" + KEY).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body("maybe"))), 400);
    }

    @Test
    void everyEndpointIsForAdminsOnly() throws Exception {
        for (var who : new org.springframework.test.web.servlet.request.RequestPostProcessor[]{asEnvAdmin(), asUser(), asViewer()}) {
            mockMvc.perform(get(URL).with(who)).andExpect(status().isForbidden());
            mockMvc.perform(put(URL + "/" + KEY).with(who).contentType(MediaType.APPLICATION_JSON).content(body("true")))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete(URL + "/" + KEY).with(who)).andExpect(status().isForbidden());
        }
    }
}
