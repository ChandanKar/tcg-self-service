package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.Environment;
import static org.assertj.core.api.Assertions.assertThat;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Teams API role matrix (E23-T01): everyone signed in reads, admins write. */
class TeamControllerIntegrationTest extends SecuredWebTestBase {

    @Autowired private TeamRepository teams;
    @Autowired private EnvironmentRepository environments;
    @Autowired private com.tcgdigital.vmcontrol.repository.TeamMemberRepository members;

    private String create(String displayName) throws Exception {
        String body = mockMvc.perform(post("/api/v1/teams").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"" + displayName + "\",\"costCenter\":\"CC-42\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.teamId");
    }

    @Test
    void usersReadAdminsWrite() throws Exception {
        String id = create("Platform Team");
        mockMvc.perform(get("/api/v1/teams").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("platform-team"))
                .andExpect(jsonPath("$[0].costCenter").value("CC-42"));
        mockMvc.perform(get("/api/v1/teams/" + id).with(asViewer())).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/teams").with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Nope\"}")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/teams/" + id).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Nope\"}")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/teams/" + id).with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/teams/" + id).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Platform\",\"costCenter\":\"CC-7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Platform"));
    }

    @Test
    void deactivatingATeamThatOwnsAnEnvironmentIs409() throws Exception {
        String id = create("Owner");
        Environment env = newEnvironment("Owned env");
        env.setTeam(teams.findById(id).orElseThrow());
        environments.saveAndFlush(env);

        expectError(mockMvc.perform(delete("/api/v1/teams/" + id).with(asAdmin())), 409);
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId()).with(asAdmin()))
                .andExpect(jsonPath("$.teamId").value(id))
                .andExpect(jsonPath("$.teamName").value("Owner"));
        expectError(mockMvc.perform(post("/api/v1/teams").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\" \"}")), 400);
    }

    @Test
    void environmentsTakeATeamOnCreateAndEditAndTheChangeIsAudited() throws Exception {
        String id = create("Platform");
        String body = mockMvc.perform(post("/api/v1/environments").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"team-env-" + System.nanoTime() + "\",\"displayName\":\"Team env\",\"teamId\":\"" + id + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.teamId").value(id))
                .andExpect(jsonPath("$.teamName").value("Platform"))
                .andReturn().getResponse().getContentAsString();
        String envId = com.jayway.jsonpath.JsonPath.read(body, "$.environmentId");

        mockMvc.perform(put("/api/v1/environments/" + envId).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Team env\"}"))
                .andExpect(jsonPath("$.teamId").value(id)); // not sent: unchanged
        mockMvc.perform(put("/api/v1/environments/" + envId).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").doesNotExist());
        mockMvc.perform(put("/api/v1/environments/" + envId).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\":\"no-such-team\"}")).andExpect(status().isNotFound());
        awaitAsync(() -> org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE environment_id = ? AND action_type = 'ENVIRONMENT_UPDATED' AND details LIKE '%team%'",
                Integer.class, envId)).isEqualTo(1));
    }

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ---- U13-T06: overview and a team's environments

    @Test
    void theOverviewListsEachTeamsEnvironmentsAndItsEntraGroup() throws Exception {
        String id = create("Payments");
        Environment a = newEnvironment("Payments Dev");
        Environment b = newEnvironment("Payments QA");
        for (Environment e : java.util.List.of(a, b)) {
            e.setTeam(teams.findById(id).orElseThrow());
            environments.saveAndFlush(e);
        }
        mockMvc.perform(put("/api/v1/teams/" + id + "/entra-group").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"grp-1\",\"groupName\":\"SG-Payments-Engineering\"}"))
                .andExpect(status().isOk());
        java.sql.Timestamp synced = java.sql.Timestamp.valueOf("2026-10-20 09:00:00");
        members.saveAndFlush(new com.tcgdigital.vmcontrol.model.TeamMember(id, operator.getUserId(),
                com.tcgdigital.vmcontrol.model.TeamMember.Source.ENTRA, synced));
        members.saveAndFlush(new com.tcgdigital.vmcontrol.model.TeamMember(id, viewer.getUserId(),
                com.tcgdigital.vmcontrol.model.TeamMember.Source.MANUAL, synced));

        String all = mockMvc.perform(get("/api/v1/teams/overview").with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, Object>> mine = com.jayway.jsonpath.JsonPath.read(all, "$[?(@.teamId == '" + id + "')]");
        assertThat(mine).hasSize(1);
        java.util.Map<String, Object> payments = mine.get(0);
        assertThat(payments.get("entraGroupName")).isEqualTo("SG-Payments-Engineering");
        assertThat(((Number) payments.get("memberCount")).longValue()).isEqualTo(2);
        assertThat(((Number) payments.get("entraMemberCount")).longValue()).isEqualTo(1);
        assertThat(payments.get("lastSyncedAt")).isNotNull();
        assertThat(com.jayway.jsonpath.JsonPath.<java.util.List<String>>read(all, "$[?(@.teamId == '" + id + "')].environments[*].environmentId"))
                .containsExactlyInAnyOrder(a.getEnvironmentId(), b.getEnvironmentId());
        mockMvc.perform(get("/api/v1/teams/overview").with(asEnvAdmin())).andExpect(status().isForbidden());

        // Unlinking clears the name.
        mockMvc.perform(put("/api/v1/teams/" + id + "/entra-group").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":null}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/teams/" + id + "/overview").with(asAdmin()))
                .andExpect(jsonPath("$.entraGroupName").doesNotExist());
    }

    @Test
    void settingATeamsEnvironmentsMovesTheListedOnesAndFreesTheOthers() throws Exception {
        String id = create("Data");
        String other = create("Other");
        Environment kept = newEnvironment("Data lake");
        Environment dropped = newEnvironment("Data old");
        Environment moved = newEnvironment("From other");
        kept.setTeam(teams.findById(id).orElseThrow());
        dropped.setTeam(teams.findById(id).orElseThrow());
        moved.setTeam(teams.findById(other).orElseThrow());
        environments.saveAndFlush(kept);
        environments.saveAndFlush(dropped);
        environments.saveAndFlush(moved);

        mockMvc.perform(put("/api/v1/teams/" + id + "/environments").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"environmentIds\":[\"" + kept.getEnvironmentId() + "\",\"" + moved.getEnvironmentId() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environments.length()").value(2));
        assertThat(environments.findById(dropped.getEnvironmentId()).orElseThrow().getTeam()).isNull();
        assertThat(environments.findById(moved.getEnvironmentId()).orElseThrow().getTeam().getTeamId()).isEqualTo(id);
        assertThat(environments.findById(kept.getEnvironmentId()).orElseThrow().getTeam().getTeamId()).isEqualTo(id);

        Environment gone = newEnvironment("Gone env");
        gone.setIsActive(false);
        environments.saveAndFlush(gone);
        expectError(mockMvc.perform(put("/api/v1/teams/" + id + "/environments").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"environmentIds\":[\"" + gone.getEnvironmentId() + "\"]}")), 400)
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Gone env is deactivated")));
        expectError(mockMvc.perform(put("/api/v1/teams/" + id + "/environments").with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"environmentIds\":[]}")), 403);
        assertThat(environments.findById(kept.getEnvironmentId()).orElseThrow().getTeam().getTeamId()).isEqualTo(id);
    }
}
