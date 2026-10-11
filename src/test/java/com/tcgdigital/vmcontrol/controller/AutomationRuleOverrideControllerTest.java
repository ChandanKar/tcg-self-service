package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.AutomationRuleRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may override a rule (E19-T03): admins of its environment for up to 30 days, anyone who can
 * operate there for up to 24 hours (a snooze); viewers and outsiders not at all.
 */
class AutomationRuleOverrideControllerTest extends SecuredWebTestBase {

    @Autowired private AutomationRuleRepository rules;

    private Environment env;
    private AutomationRule rule;

    @BeforeEach
    void setUpRule() {
        env = newEnvironment("Overrides");
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
        AutomationRule r = new AutomationRule();
        r.setRuleId(UUID.randomUUID().toString());
        r.setName("Nightly stop");
        r.setEnvironment(env);
        r.setScopeType(AutomationScopeType.ENVIRONMENT);
        r.setTriggerType(AutomationTriggerType.SCHEDULE);
        r.addWindow("MON,TUE,WED,THU,FRI", null, "20:00");
        r.setTimezone("Asia/Kolkata");
        r.setEnabled(true);
        r.setCreatedByUserId(admin.getUserId());
        rule = rules.saveAndFlush(r);
    }

    private String body(Duration length) {
        Instant start = Instant.now().plus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        return "{\"startsAt\":\"" + start + "\",\"endsAt\":\"" + start.plus(length)
                + "\",\"skipAction\":\"STOP\",\"reason\":\"release\"}";
    }

    private String url() {
        return "/api/v1/automation-rules/" + rule.getRuleId() + "/overrides";
    }

    @Test
    void aUserMaySnoozeForADayButNotThreeDays() throws Exception {
        expectError(mockMvc.perform(post(url()).with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(body(Duration.ofDays(3)))), 403);
        mockMvc.perform(post(url()).with(asUser()).contentType(MediaType.APPLICATION_JSON).content(body(Duration.ofHours(12))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skipAction").value("STOP"))
                .andExpect(jsonPath("$.createdByDisplayName").value("operator"));
        mockMvc.perform(get(url()).with(asUser())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void adminsMayOverrideForLongerAndRemoveAnyone() throws Exception {
        String created = mockMvc.perform(post(url()).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Duration.ofHours(2))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String overrideId = com.jayway.jsonpath.JsonPath.read(created, "$.overrideId");

        mockMvc.perform(post(url()).with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(body(Duration.ofDays(3))))
                .andExpect(status().isOk());
        mockMvc.perform(delete(url() + "/" + overrideId).with(asAdmin())).andExpect(status().isNoContent());
    }

    @Test
    void viewersAndOutsidersCannotOverride() throws Exception {
        expectError(mockMvc.perform(post(url()).with(asViewer())
                .contentType(MediaType.APPLICATION_JSON).content(body(Duration.ofHours(1)))), 403);
        expectError(mockMvc.perform(get(url()).with(asViewer())), 403);
    }

    @Test
    void onlyTheAuthorOrAnAdminRemovesAnOverride() throws Exception {
        String created = mockMvc.perform(post(url()).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Duration.ofHours(2))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String overrideId = com.jayway.jsonpath.JsonPath.read(created, "$.overrideId");
        expectError(mockMvc.perform(delete(url() + "/" + overrideId).with(asUser())), 403);
    }

    @Test
    void anOverrideEndingBeforeItStartsIs400() throws Exception {
        Instant start = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        String backwards = "{\"startsAt\":\"" + start + "\",\"endsAt\":\"" + start.minus(Duration.ofHours(1))
                + "\",\"skipAction\":\"STOP\",\"reason\":\"release\"}";
        expectError(mockMvc.perform(post(url()).with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(backwards)), 400)
                .andExpect(jsonPath("$.message").value("The override must end after it starts"));
        expectError(mockMvc.perform(post(url()).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startsAt\":\"" + start + "\",\"endsAt\":\"" + start.plus(Duration.ofHours(1))
                        + "\",\"skipAction\":\"STOP\",\"reason\":\" \"}")), 400);
    }
}
