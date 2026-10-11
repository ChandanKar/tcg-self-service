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

    @org.springframework.beans.factory.annotation.Autowired
    private com.tcgdigital.vmcontrol.repository.CostAnomalyRepository anomalies;

    private com.tcgdigital.vmcontrol.model.Environment anomalyOn(String id) {
        com.tcgdigital.vmcontrol.model.Environment env = newEnvironment("Anomalous");
        com.tcgdigital.vmcontrol.model.CostAnomaly a = new com.tcgdigital.vmcontrol.model.CostAnomaly();
        a.setAnomalyId(id);
        a.setEnvironmentId(env.getEnvironmentId());
        a.setAnomalyStart(java.time.LocalDate.now().minusDays(2));
        a.setTotalImpact(new java.math.BigDecimal("120.00"));
        a.setFirstSeenAt(new java.sql.Timestamp(System.currentTimeMillis()));
        a.setLastSeenAt(new java.sql.Timestamp(System.currentTimeMillis()));
        anomalies.saveAndFlush(a);
        return env;
    }

    @Test
    void theAdminListIsAdminOnlyAndScopedListsFollowViewAccess() throws Exception {
        com.tcgdigital.vmcontrol.model.Environment env = anomalyOn("an-1");
        grantEnv(viewer, env.getEnvironmentId(), com.tcgdigital.vmcontrol.model.AccessLevel.VIEWER);

        mockMvc.perform(get("/api/v1/cost-anomalies?days=30").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].anomalyId").value("an-1"));
        mockMvc.perform(get("/api/v1/cost-anomalies").with(asEnvAdmin())).andExpect(status().isForbidden());
        expectError(mockMvc.perform(get("/api/v1/cost-anomalies?days=0").with(asAdmin())), 400);

        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/cost-anomalies").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].canGiveFeedback").value(false));
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/cost-anomalies").with(asUser()))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownersGiveFeedbackViewersCannot() throws Exception {
        com.tcgdigital.vmcontrol.model.Environment env = anomalyOn("an-2");
        grantEnv(operator, env.getEnvironmentId(), com.tcgdigital.vmcontrol.model.AccessLevel.ADMIN);
        grantEnv(viewer, env.getEnvironmentId(), com.tcgdigital.vmcontrol.model.AccessLevel.VIEWER);

        mockMvc.perform(post("/api/v1/cost-anomalies/an-2/feedback").with(asUser()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"NO\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.feedback").value("NO"));
        mockMvc.perform(post("/api/v1/cost-anomalies/an-2/feedback").with(asViewer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"YES\"}")).andExpect(status().isForbidden());
    }
}
