package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Anomaly monitor administration is for admins only (E21-T01). */
class CostAnomalyControllerSecurityTest extends SecuredWebTestBase {

    @Test
    void monitorsAreAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/cost-anomalies/monitors").with(asAdmin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cost-anomalies/monitors").with(asEnvAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/cost-anomalies/monitors/sync").with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/cost-anomalies/monitors/link").with(asViewer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"monitorArn\":\"arn:x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void linkingAnUnknownMonitorIs404() throws Exception {
        mockMvc.perform(put("/api/v1/cost-anomalies/monitors/link").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"monitorArn\":\"arn:aws:ce::1:anomalymonitor/abc\",\"environmentId\":null}"))
                .andExpect(status().isNotFound());
        expectError(mockMvc.perform(put("/api/v1/cost-anomalies/monitors/link").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"monitorArn\":\" \"}")), 400);
    }
}
