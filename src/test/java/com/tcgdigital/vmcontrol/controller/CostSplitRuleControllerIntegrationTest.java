package com.tcgdigital.vmcontrol.controller;

import com.jayway.jsonpath.JsonPath;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Split-rule API (E23-T05): validation, one rule per environment, admins only, audited. */
class CostSplitRuleControllerIntegrationTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/cost-management/split-rules";

    @Autowired private AuditLogRepository auditLogs;

    private String team(String displayName) throws Exception {
        String body = mockMvc.perform(post("/api/v1/teams").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + displayName + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.teamId");
    }

    private static String body(String envId, String method, String... shares) {
        StringBuilder s = new StringBuilder("{\"environmentId\":\"" + envId + "\",\"method\":\"" + method
                + "\",\"effectiveFrom\":\"2026-09-15\",\"shares\":[");
        for (int i = 0; i < shares.length; i++) {
            s.append(i > 0 ? "," : "").append(shares[i]);
        }
        return s.append("]}").toString();
    }

    private static String share(String teamId, String percent) {
        return "{\"teamId\":\"" + teamId + "\"" + (percent == null ? "" : ",\"percent\":" + percent) + "}";
    }

    @Test
    void anAdminCreatesUpdatesAndDeletesARule() throws Exception {
        String x = team("Team X");
        String y = team("Team Y");
        Environment shared = newEnvironment("Shared CI");

        String created = mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(shared.getEnvironmentId(), "FIXED_PERCENT", share(x, "60"), share(y, "40.00"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.environmentName").value("Shared CI"))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-09-01"))
                .andExpect(jsonPath("$.shares[0].teamName").value("Team X"))
                .andExpect(jsonPath("$.shares[0].percent").value(60.00))
                .andReturn().getResponse().getContentAsString();
        String ruleId = JsonPath.read(created, "$.ruleId");

        mockMvc.perform(get(URL).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleId").value(ruleId))
                .andExpect(jsonPath("$[0].shares[1].teamName").value("Team Y"));

        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body(shared.getEnvironmentId(), "EVEN", share(x, null)))), 409);

        // Reordering the teams on update does not trip the (rule, team) key.
        mockMvc.perform(put(URL + "/" + ruleId).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(shared.getEnvironmentId(), "EVEN", share(y, "99"), share(x, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("EVEN"))
                .andExpect(jsonPath("$.shares[0].teamId").value(y))
                .andExpect(jsonPath("$.shares[0].percent").doesNotExist());

        mockMvc.perform(delete(URL + "/" + ruleId).with(asAdmin())).andExpect(status().isNoContent());
        mockMvc.perform(get(URL + "/" + ruleId).with(asAdmin())).andExpect(status().isNotFound());

        awaitAsync(() -> assertThat(auditLogs.findAll()).extracting(a -> a.getAction())
                .contains(AuditAction.COST_SPLIT_RULE_CREATED, AuditAction.COST_SPLIT_RULE_UPDATED,
                        AuditAction.COST_SPLIT_RULE_DELETED));
    }

    @Test
    void invalidRulesAre400() throws Exception {
        String x = team("Alpha");
        String y = team("Beta");
        String env = newEnvironment("Shared DB").getEnvironmentId();

        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(env, "FIXED_PERCENT", share(x, "50"), share(y, "40")))), 400)
                .andExpect(jsonPath("$.message").value("Percentages must add up to 100 (they add up to 90.00)"));
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body(env, "EVEN"))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body(env, "EVEN", share(x, null), share(x, null)))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body(env, "SOMETIMES", share(x, null)))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body(env, "FIXED_PERCENT", share(x, "33.333"), share(y, "66.667")))), 400);

        mockMvc.perform(delete("/api/v1/teams/" + y).with(asAdmin())).andExpect(status().is2xxSuccessful());
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(env, "EVEN", share(x, null), share(y, null)))), 400)
                .andExpect(jsonPath("$.message").value("Team Beta is inactive"));
    }

    @Test
    void onlyAdminsMaySeeOrChangeRules() throws Exception {
        String env = newEnvironment("Shared").getEnvironmentId();
        mockMvc.perform(get(URL).with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).with(asEnvAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body(env, "EVEN"))).andExpect(status().isForbidden());
        mockMvc.perform(put(URL + "/x").with(asViewer()).contentType(MediaType.APPLICATION_JSON)
                .content(body(env, "EVEN"))).andExpect(status().isForbidden());
        mockMvc.perform(delete(URL + "/x").with(asUser())).andExpect(status().isForbidden());
    }
}
