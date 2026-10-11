package com.tcgdigital.vmcontrol.controller;

import com.jayway.jsonpath.JsonPath;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.ApprovalPolicyRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.service.ApprovalPolicyService;
import com.tcgdigital.vmcontrol.service.TeamService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Approval policies API (E25-T03): role matrix, validation, layering and the dry run. */
class ApprovalPolicyControllerIntegrationTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/approval-policies";

    @Autowired private ApprovalPolicyService policyService;
    @Autowired private ApprovalPolicyRepository policies;
    @Autowired private TeamRepository teams;

    private Object target() {
        Object target = AopTestUtils.getTargetObject(policyService);
        return target;
    }

    @AfterEach
    void off() {
        ReflectionTestUtils.setField(target(), "enabled", false);
    }

    private static String body(String envId, String teamId, String name, String extra) {
        return "{" + (envId == null ? "" : "\"environmentId\":\"" + envId + "\",")
                + (teamId == null ? "" : "\"teamId\":\"" + teamId + "\",")
                + "\"name\":\"" + name + "\"," + extra + "}";
    }

    private String create(String json, org.springframework.test.web.servlet.request.RequestPostProcessor as) throws Exception {
        String out = mockMvc.perform(post(URL).with(as).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(out, "$.policyId");
    }

    @Test
    void rolesAndValidation() throws Exception {
        Environment env = newEnvironment("Gated");
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.ADMIN);

        String global = create(body(null, null, "Big boxes",
                "\"instanceTypePatterns\":[\"*.8xlarge\",\"p*\"],\"approverMode\":\"ALL\",\"approvers\":[{\"type\":\"ENV_ADMINS\"}]"), asAdmin());
        mockMvc.perform(get(URL + "/" + global).with(asAdmin()))
                .andExpect(jsonPath("$.approverMode").value("ALL"))
                .andExpect(jsonPath("$.approvers[0].label").value("Environment admins"));

        // The environment's admin manages its own policies, not global ones.
        create(body(env.getEnvironmentId(), null, "Long runs", "\"maxRuntimeHours\":12,\"approvers\":[{\"type\":\"USER\",\"approverId\":\""
                + admin.getUserId() + "\"}]"), asUser());
        mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body(null, null, "Nope", "\"maxRuntimeHours\":1"))).andExpect(status().isForbidden());
        mockMvc.perform(put(URL + "/" + global).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content(body(null, null, "Nope", "\"maxRuntimeHours\":1"))).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).param("environmentId", env.getEnvironmentId()).with(asViewer())).andExpect(status().isForbidden());

        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body(null, null, "Empty", "\"approverMode\":\"ANY\""))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body(null, null, "Bad mode", "\"minVcpu\":8,\"approverMode\":\"SOME\""))), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(body(null, null, "Bad approver", "\"minVcpu\":8,\"approvers\":[{\"type\":\"USER\",\"approverId\":\"ghost\"}]"))), 400);

        mockMvc.perform(delete(URL + "/" + global).with(asAdmin())).andExpect(status().isNoContent());
        assertThat(policies.count()).isEqualTo(1);
    }

    @Test
    void globalEnvironmentAndTeamPoliciesApplyOthersDoNot() throws Exception {
        Team team = new Team();
        team.setTeamId(UUID.randomUUID().toString());
        team.setName(TeamService.slug("Gate team"));
        team.setDisplayName("Gate team");
        team = teams.saveAndFlush(team);
        Environment env = newEnvironment("Layered");
        env.setTeam(team);
        environmentRepository.saveAndFlush(env);
        Environment other = newEnvironment("Other");

        create(body(null, null, "G", "\"maxRuntimeHours\":100"), asAdmin());
        create(body(env.getEnvironmentId(), null, "E", "\"maxRuntimeHours\":100"), asAdmin());
        create(body(null, team.getTeamId(), "T", "\"maxRuntimeHours\":100"), asAdmin());
        create(body(other.getEnvironmentId(), null, "X", "\"maxRuntimeHours\":100"), asAdmin());

        assertThat(policies.findActiveForEnvironment(env.getEnvironmentId(), team.getTeamId()))
                .extracting(p -> p.getName()).containsExactlyInAnyOrder("G", "E", "T");
        assertThat(policies.findActiveForEnvironment(other.getEnvironmentId(), null))
                .extracting(p -> p.getName()).containsExactlyInAnyOrder("G", "X");
    }

    @Test
    void theDryRunExplainsTheDecision() throws Exception {
        Environment env = newEnvironment("Dry");
        VmGroup group = newGroup(env, "app");
        newVm(group, "app-1", VmStatus.STOPPED);
        create(body(env.getEnvironmentId(), null, "Long runs", "\"maxRuntimeHours\":12"), asAdmin());
        String dry = "{\"environmentId\":\"" + env.getEnvironmentId() + "\",\"hours\":24}";

        mockMvc.perform(post(URL + "/evaluate").with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(dry))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiresApproval").value(false)); // approvals off

        ReflectionTestUtils.setField(target(), "enabled", true);
        mockMvc.perform(post(URL + "/evaluate").with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(dry))
                .andExpect(jsonPath("$.outcome").value("REQUIRE_APPROVAL"))
                .andExpect(jsonPath("$.matches[0].name").value("Long runs"))
                .andExpect(jsonPath("$.reasons[0]").value("24 h is longer than 12 h"));
        mockMvc.perform(post(URL + "/evaluate").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environmentId\":\"" + env.getEnvironmentId() + "\",\"hours\":6}"))
                .andExpect(jsonPath("$.outcome").value("ALLOW"));
        mockMvc.perform(post(URL + "/evaluate").with(asViewer()).contentType(MediaType.APPLICATION_JSON).content(dry))
                .andExpect(status().isForbidden());
    }
}
