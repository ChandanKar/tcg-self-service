package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.IdleStopMode;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.IdleStopRuleRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Idle auto-stop rule API (E16-T01): who can configure it, dry run first, enforce gating, and
 * production exclusion.
 */
class IdleStopControllerTest extends SecuredWebTestBase {

    @Autowired private IdleStopRuleRepository rules;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment envA;
    private Environment envB;

    @BeforeEach
    void seed() {
        envA = newEnvironment("IdleA");
        envB = newEnvironment("IdleB");
        grantEnv(operator, envA.getEnvironmentId(), AccessLevel.USER);
        grantEnv(viewer, envA.getEnvironmentId(), AccessLevel.VIEWER);
    }

    private String rulesUrl(Environment env) {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/idle-stop/rules";
    }

    private String create(Environment env, String body) throws Exception {
        String json = mockMvc.perform(post(rulesUrl(env)).with(asEnvAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.ruleId");
    }

    @Test
    void anEnvAdminCreatesARuleThatStartsInDryRunWithDefaults() throws Exception {
        mockMvc.perform(post(rulesUrl(envA)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"ENFORCE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("DRY_RUN"))
                .andExpect(jsonPath("$.idleMinutes").value(60))
                .andExpect(jsonPath("$.cpuMaxPercent").value(5.0))
                .andExpect(jsonPath("$.networkMbPerDay").value(5.0))
                .andExpect(jsonPath("$.scopeType").value("ENVIRONMENT"))
                .andExpect(jsonPath("$.dryRunStartedAt").isNotEmpty());

        mockMvc.perform(get(rulesUrl(envA)).with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action_type = 'IDLE_STOP_RULE_CHANGED' AND environment_id = ?",
                Integer.class, envA.getEnvironmentId())).isEqualTo(1));
    }

    @Test
    void usersAndViewersCannotConfigure() throws Exception {
        mockMvc.perform(post(rulesUrl(envA)).with(asUser()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(rulesUrl(envA)).with(asViewer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void thresholdsAreValidated() throws Exception {
        expectError(mockMvc.perform(post(rulesUrl(envA)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"idleMinutes\":10}")), 400);
        expectError(mockMvc.perform(post(rulesUrl(envA)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cpuMaxPercent\":50}")), 400);
    }

    @Test
    void aGroupOfAnotherEnvironmentIsNotFound() throws Exception {
        VmGroup groupOfB = newGroup(envB, "web");

        expectError(mockMvc.perform(post(rulesUrl(envA)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scopeType\":\"GROUP\",\"groupId\":\"" + groupOfB.getGroupId() + "\"}")), 404);
    }

    @Test
    void aGroupRuleIsScopedToItsGroup() throws Exception {
        VmGroup web = newGroup(envA, "web");

        mockMvc.perform(post(rulesUrl(envA)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"GROUP\",\"groupId\":\"" + web.getGroupId() + "\",\"idleMinutes\":90}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(web.getGroupId()))
                .andExpect(jsonPath("$.idleMinutes").value(90));
    }

    @Test
    void enforceNeedsAFullDryRunUnlessAnAdminOverrides() throws Exception {
        String ruleId = create(envA, "{}");
        jdbcTemplate.update("UPDATE idle_stop_rule SET dry_run_started_at = ? WHERE rule_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(3))), ruleId);

        expectError(mockMvc.perform(put(rulesUrl(envA) + "/" + ruleId).with(asEnvAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ENFORCE\"}")), 400)
                .andExpect(jsonPath("$.message").value("Run in dry-run mode for 14 days first"));

        mockMvc.perform(put(rulesUrl(envA) + "/" + ruleId).with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ENFORCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ENFORCE"));
    }

    @Test
    void anEnvAdminCanEnforceAfterFourteenDays() throws Exception {
        String ruleId = create(envA, "{}");
        jdbcTemplate.update("UPDATE idle_stop_rule SET dry_run_started_at = ? WHERE rule_id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(15))), ruleId);

        mockMvc.perform(put(rulesUrl(envA) + "/" + ruleId).with(asEnvAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ENFORCE\"}"))
                .andExpect(status().isOk());
        assertThat(rules.findById(ruleId).orElseThrow().getMode()).isEqualTo(IdleStopMode.ENFORCE);
    }

    @Test
    void aRuleIsOnlyReachableThroughItsEnvironment() throws Exception {
        String ruleId = create(envA, "{}");

        expectError(mockMvc.perform(put(rulesUrl(envB) + "/" + ruleId).with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"idleMinutes\":120}")), 404);
        expectError(mockMvc.perform(delete(rulesUrl(envB) + "/" + ruleId).with(asAdmin())), 404);
        mockMvc.perform(delete(rulesUrl(envA) + "/" + ruleId).with(asEnvAdmin())).andExpect(status().isNoContent());
        assertThat(rules.findById(ruleId)).isEmpty();
    }

    @Test
    void productionEnvironmentsCannotHaveRules() throws Exception {
        String url = "/api/v1/environments/" + envA.getEnvironmentId() + "/production";
        mockMvc.perform(patch(url).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"production\":true}")).andExpect(status().isForbidden());
        mockMvc.perform(patch(url).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"production\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.production").value(true));

        expectError(mockMvc.perform(post(rulesUrl(envA)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")), 400)
                .andExpect(jsonPath("$.message").value("Production environments are excluded from idle auto-stop"));
    }

    @Test
    void oneEnvironmentRulePerEnvironment() throws Exception {
        create(envA, "{}");

        expectError(mockMvc.perform(post(rulesUrl(envA)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")), 400);
    }

    // ---- E16-T04: snooze and status ----

    private String snoozeUrl(Environment env) {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/idle-stop/snooze";
    }

    @Test
    void aUserSnoozesForFourHoursAndItIsAudited() throws Exception {
        mockMvc.perform(post(snoozeUrl(envA)).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":4,\"reason\":\"demo this afternoon\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snoozedUntil").isNotEmpty());

        mockMvc.perform(get("/api/v1/environments/" + envA.getEnvironmentId() + "/idle-stop/status").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snoozedByUserId").value(operator.getUserId()))
                .andExpect(jsonPath("$.featureEnabled").value(false))
                .andExpect(jsonPath("$.canOperate").value(false))      // a viewer
                .andExpect(jsonPath("$.canAdminister").value(false))
                .andExpect(jsonPath("$.days").value(14))
                .andExpect(jsonPath("$.wouldHaveSaved").value(0));
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action_type = 'IDLE_STOP_SNOOZED' AND environment_id = ?",
                Integer.class, envA.getEnvironmentId())).isEqualTo(1));
    }

    @Test
    void onlyOneFourOrEightHoursAndNotForViewers() throws Exception {
        expectError(mockMvc.perform(post(snoozeUrl(envA)).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":3}")), 400);
        mockMvc.perform(post(snoozeUrl(envA)).with(asViewer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":4}")).andExpect(status().isForbidden());
    }

    @Test
    void aShorterSnoozeDoesNotShortenAnActiveOne() throws Exception {
        String longer = JsonPath.read(mockMvc.perform(post(snoozeUrl(envA)).with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content("{\"hours\":8}"))
                .andReturn().getResponse().getContentAsString(), "$.snoozedUntil").toString();

        String after = JsonPath.read(mockMvc.perform(post(snoozeUrl(envA)).with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content("{\"hours\":1}"))
                .andReturn().getResponse().getContentAsString(), "$.snoozedUntil").toString();

        assertThat(after).isEqualTo(longer);
    }

    @Test
    void theSnoozerOrAnAdminEndsTheSnooze() throws Exception {
        mockMvc.perform(post(snoozeUrl(envA)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"hours\":4}")).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/environments/" + envA.getEnvironmentId() + "/idle-stop/status").with(asUser()))
                .andExpect(jsonPath("$.canOperate").value(true))
                .andExpect(jsonPath("$.canAdminister").value(false));
        mockMvc.perform(get("/api/v1/environments/" + envA.getEnvironmentId() + "/idle-stop/status").with(asEnvAdmin()))
                .andExpect(jsonPath("$.canAdminister").value(true));
        mockMvc.perform(delete(snoozeUrl(envA)).with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(delete(snoozeUrl(envA)).with(asAdmin())).andExpect(status().isNoContent());
        expectError(mockMvc.perform(delete(snoozeUrl(envA)).with(asAdmin())), 404);
    }
}
