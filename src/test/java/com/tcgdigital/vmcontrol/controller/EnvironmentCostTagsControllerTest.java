package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U11-T06: an environment's settings show, read-only, the cost-allocation tags the platform puts on
 * every one of its VMs.
 */
class EnvironmentCostTagsControllerTest extends SecuredWebTestBase {

    @Autowired private TeamRepository teamRepository;

    private Environment env;

    @BeforeEach
    void seed() {
        Team team = new Team();
        team.setTeamId(UUID.randomUUID().toString());
        team.setName("payments-" + UUID.randomUUID().toString().substring(0, 8));
        team.setDisplayName("Payments");
        team.setCostCenter("CC-4105");
        team.setIsActive(true);
        team = teamRepository.saveAndFlush(team);
        env = newEnvironment("Payments Dev");
        env.setTeam(team);
        env = environmentRepository.saveAndFlush(env);
    }

    @Test
    void theTagsOnEveryVmAreListed() throws Exception {
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/cost-tags").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").isBoolean())
                .andExpect(jsonPath("$.tags[?(@.key == 'tcg:managed-by')]").exists())
                .andExpect(jsonPath("$.tags[?(@.key == 'tcg:environment')].value").value(env.getName()))
                .andExpect(jsonPath("$.tags[?(@.key == 'tcg:cost-center')].value").value("CC-4105"));
    }

    @Test
    void operatorsCannotSeeThem() throws Exception {
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/cost-tags").with(asUser()))
                .andExpect(status().isForbidden());
    }
}
