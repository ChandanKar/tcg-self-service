package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.SpotPolicy;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spot opt-in API (E31-T03): the switch, who may change it, the acknowledgement, production and exclusions. */
class SpotControllerTest extends SecuredWebTestBase {

    @Autowired private SettingsService settings;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private Environment other;

    @BeforeEach
    void seed() {
        env = newEnvironment("SpotEnv");
        other = newEnvironment("OtherEnv");
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
        settings.set("spot.enabled", "true", admin.getUserId());
    }

    @AfterEach
    void switchOff() {
        settings.reset("spot.enabled", admin.getUserId());
    }

    private String policyUrl(Environment e) {
        return "/api/v1/environments/" + e.getEnvironmentId() + "/spot-policy";
    }

    @Test
    void anEnvAdminAllowsSpotWithTheAcknowledgementAndItIsAudited() throws Exception {
        mockMvc.perform(put(policyUrl(env)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policy\":\"ALLOWED\",\"acknowledgeInterruptible\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spotPolicy").value("ALLOWED"));

        assertThat(environmentRepository.findById(env.getEnvironmentId()).orElseThrow().getSpotPolicy()).isEqualTo(SpotPolicy.ALLOWED);
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action_type = 'SPOT_POLICY_CHANGED' AND environment_id = ?",
                Integer.class, env.getEnvironmentId())).isEqualTo(1));
    }

    @Test
    void withoutTheAcknowledgementItIsRefused() throws Exception {
        expectError(mockMvc.perform(put(policyUrl(env)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"ALLOWED\"}")), 400);
        expectError(mockMvc.perform(put(policyUrl(env)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"SOMETIMES\"}")), 400);
    }

    @Test
    void aProductionEnvironmentCannotUseSpot() throws Exception {
        Environment prod = environmentRepository.findById(env.getEnvironmentId()).orElseThrow();
        prod.setIsProduction(true);
        environmentRepository.save(prod);

        expectError(mockMvc.perform(put(policyUrl(env)).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"ALLOWED\",\"acknowledgeInterruptible\":true}")), 400);
    }

    @Test
    void usersAndViewersCannotChangeIt() throws Exception {
        String body = "{\"policy\":\"ALLOWED\",\"acknowledgeInterruptible\":true}";
        mockMvc.perform(put(policyUrl(env)).with(asUser()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(policyUrl(env)).with(asViewer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void turnedOffNobodyCanChangeIt() throws Exception {
        settings.reset("spot.enabled", admin.getUserId());

        expectError(mockMvc.perform(put(policyUrl(env)).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"ALLOWED\",\"acknowledgeInterruptible\":true}")), 400);
    }

    @Test
    void aVmIsExcludedOnlyThroughItsOwnEnvironment() throws Exception {
        Vm vm = newVm(newGroup(env, "workers"), "worker-1", VmStatus.RUNNING);

        mockMvc.perform(put("/api/v1/environments/" + env.getEnvironmentId() + "/vms/" + vm.getVmId() + "/spot-excluded")
                        .with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"excluded\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spotExcluded").value(true));
        assertThat(vmRepository.findById(vm.getVmId()).orElseThrow().isSpotExcluded()).isTrue();

        expectError(mockMvc.perform(put("/api/v1/environments/" + other.getEnvironmentId() + "/vms/" + vm.getVmId() + "/spot-excluded")
                .with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"excluded\":false}")), 404);
    }
}
