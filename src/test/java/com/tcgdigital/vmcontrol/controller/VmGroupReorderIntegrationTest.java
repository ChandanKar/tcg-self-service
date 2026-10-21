package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U11-T02: the start order is set in one call; a group cannot start before one it waits for.
 */
class VmGroupReorderIntegrationTest extends SecuredWebTestBase {

    private Environment env;
    private VmGroup database;
    private VmGroup application;
    private VmGroup web;
    private VmGroup discovered;

    private VmGroup group(String displayName, String name, int position, List<String> dependsOn) {
        VmGroup group = newGroup(env, displayName);
        if (name != null) {
            group.setName(name);
        }
        group.setSequencePosition(position);
        group.setDependencies(dependsOn);
        return vmGroupRepository.saveAndFlush(group);
    }

    @BeforeEach
    void seed() {
        env = newEnvironment("Payments Dev");
        database = group("Database", null, 1, List.of());
        application = group("Application", null, 2, List.of(database.getGroupId()));
        web = group("Web", null, 3, List.of(application.getGroupId()));
        discovered = group("Auto-Discovered", "discovered", 999, List.of());
    }

    private ResultActions order(RequestPostProcessor who, VmGroup... groups) throws Exception {
        String ids = String.join(",", java.util.Arrays.stream(groups).map(g -> "\"" + g.getGroupId() + "\"").toList());
        return mockMvc.perform(put("/api/v1/environments/" + env.getEnvironmentId() + "/groups/order").with(who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"groupIds\":[" + ids + "]}"));
    }

    private List<String> namesInOrder() {
        return vmGroupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(env.getEnvironmentId()).stream()
                .map(g -> g.getDisplayName() + "@" + g.getSequencePosition()).toList();
    }

    @Test
    void theOrderIsSetInOneCall() throws Exception {
        // Application and Database have no order between them once Application stops waiting for Database.
        application.setDependencies(List.of());
        vmGroupRepository.saveAndFlush(application);

        order(asEnvAdmin(), application, database, web)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("Application"))
                .andExpect(jsonPath("$[2].displayName").value("Web"));

        assertThat(namesInOrder()).containsExactly("Application@1", "Database@2", "Web@3", "Auto-Discovered@999");
    }

    @Test
    void aSwapOfTwoNeighboursPassesTheUniquePositions() throws Exception {
        web.setDependencies(List.of());
        vmGroupRepository.saveAndFlush(web);

        order(asEnvAdmin(), database, web, application, discovered).andExpect(status().isOk());

        assertThat(namesInOrder()).containsExactly("Database@1", "Web@2", "Application@3", "Auto-Discovered@999");
    }

    @Test
    void aGroupCannotStartBeforeOneItWaitsFor() throws Exception {
        order(asEnvAdmin(), database, web, application)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Web waits for Application, so it cannot start first"));

        assertThat(namesInOrder()).containsExactly("Database@1", "Application@2", "Web@3", "Auto-Discovered@999");
    }

    @Test
    void everyGroupMustBeListedOnce() throws Exception {
        VmGroup foreign = newGroup(newEnvironment("Other"), "x");
        for (VmGroup[] wrong : List.of(new VmGroup[] {database, application},
                new VmGroup[] {database, application, web, web},
                new VmGroup[] {database, application, web, foreign})) {
            order(asEnvAdmin(), wrong)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("The order must list each group of the environment once"));
        }
    }

    @Test
    void operatorsCannotReorder() throws Exception {
        order(asUser(), database, application, web).andExpect(status().isForbidden());
    }
}
