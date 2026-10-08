package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.AutomationRuleRepository;
import com.tcgdigital.vmcontrol.service.AutomationRuleService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/environments/{id}/cost (E18-T01): anyone who can view the environment, a
 * group-only grant gets GROUPS scope, no access is refused (403) with no cost data; and the next scheduled
 * stop/start.
 */
class EnvironmentCostControllerSecurityTest extends SecuredWebTestBase {

    @Autowired private AutomationRuleRepository rules;
    @Autowired private AutomationRuleService automationRuleService;

    private Environment envA;
    private Environment envB;
    private VmGroup web;

    @BeforeEach
    void seed() {
        envA = newEnvironment("CostA");
        envB = newEnvironment("CostB");
        web = newGroup(envA, "web");
        newGroup(envA, "db");
        newVm(web, "web-1", VmStatus.RUNNING);
        grantEnv(operator, envA.getEnvironmentId(), AccessLevel.USER);
        grantEnv(viewer, envA.getEnvironmentId(), AccessLevel.VIEWER);
    }

    private String url(Environment env) {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/cost";
    }

    @Test
    void adminsUsersAndViewersSeeTheFullCost() throws Exception {
        mockMvc.perform(get(url(envA)).with(asAdmin())).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("FULL"));
        mockMvc.perform(get(url(envA)).with(asEnvAdmin())).andExpect(status().isOk());
        mockMvc.perform(get(url(envA)).with(asUser())).andExpect(status().isOk())
                .andExpect(jsonPath("$.monthToDateEstimated").isNumber())
                .andExpect(jsonPath("$.topVms").isArray());
        mockMvc.perform(get(url(envA)).with(asViewer())).andExpect(status().isOk());
    }

    @Test
    void noAccessIsRefusedWithoutCostData() throws Exception {
        // assertCanView answers 403, as on every other environment endpoint.
        expectError(mockMvc.perform(get(url(envB)).with(asUser())), 403)
                .andExpect(jsonPath("$.monthToDateEstimated").doesNotExist());
    }

    @Test
    void anonymousIs401() throws Exception {
        mockMvc.perform(get(url(envA))).andExpect(status().isUnauthorized());
    }

    @Test
    void aGroupOnlyGrantGetsGroupScope() throws Exception {
        User groupUser = newUser("cost-group-" + UUID.randomUUID() + "@example.com", false, false);
        grantGroup(groupUser, web.getGroupId(), AccessLevel.USER);

        mockMvc.perform(get(url(envA)).with(as(groupUser)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("GROUPS"))
                .andExpect(jsonPath("$.forecastMonthEnd").doesNotExist());
    }

    @Test
    void theNextWeekdayStopIsReportedInItsTimezone() {
        AutomationRule rule = new AutomationRule();
        rule.setRuleId(UUID.randomUUID().toString());
        rule.setName("Weekday stop");
        rule.setEnvironment(envA);
        rule.setScopeType(AutomationScopeType.ENVIRONMENT);
        rule.setTriggerType(AutomationTriggerType.SCHEDULE);
        rule.setDaysOfWeek("MON,TUE,WED,THU,FRI");
        rule.setStopTime("19:00");
        rule.setStartTime("08:00");
        rule.setTimezone("Asia/Kolkata");
        rule.setEnabled(true);
        rule.setCreatedByUserId(admin.getUserId());
        rules.saveAndFlush(rule);

        // Friday 2026-10-09 20:00 IST: the next stop is Monday 19:00 IST, the next start Monday 08:00 IST.
        AutomationRuleService.NextFirings next = automationRuleService.nextScheduledFirings(
                envA.getEnvironmentId(), Instant.parse("2026-10-09T14:30:00Z"));

        assertThat(next.ruleCount()).isEqualTo(1);
        assertThat(next.nextStop()).isEqualTo(Instant.parse("2026-10-12T13:30:00Z"));
        assertThat(next.nextStart()).isEqualTo(Instant.parse("2026-10-12T02:30:00Z"));
        assertThat(automationRuleService.nextScheduledFirings(envB.getEnvironmentId(), Instant.now()).nextStop()).isNull();
    }
}
