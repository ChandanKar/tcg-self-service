package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.Environment;
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
}
