package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CreateApiTokenDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.service.ApiTokenService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/v1/environments/by-name/{name} (E34-T08): found for those who may see it, 404 for everyone else. */
class EnvironmentControllerByNameTest extends SecuredWebTestBase {

    @Autowired private ApiTokenService tokenService;

    @Test
    void anEnvironmentIsFoundByNameByThoseWhoMaySeeIt() throws Exception {
        Environment env = newEnvironment("Lookup");
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);

        mockMvc.perform(get("/api/v1/environments/by-name/" + env.getName()).with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environmentId").value(env.getEnvironmentId()));
        mockMvc.perform(get("/api/v1/environments/by-name/" + env.getName().toUpperCase()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environmentId").value(env.getEnvironmentId()));
    }

    @Test
    void anEnvironmentTheCallerCannotSeeLooksMissing() throws Exception {
        Environment env = newEnvironment("Hidden");

        expectError(mockMvc.perform(get("/api/v1/environments/by-name/" + env.getName()).with(asUser())), 404);
        expectError(mockMvc.perform(get("/api/v1/environments/by-name/no-such-environment").with(asAdmin())), 404);
    }

    @Test
    void aReadTokenFindsItToo() throws Exception {
        Object target = AopTestUtils.getTargetObject(tokenService);
        ReflectionTestUtils.setField(target, "enabled", true);
        try {
            Environment env = newEnvironment("Token lookup");
            grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
            String token = tokenService.create(operator, new CreateApiTokenDTO("tf", List.of("environments:read"), 1)).token();

            mockMvc.perform(get("/api/v1/environments/by-name/" + env.getName()).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value(env.getName()));
        } finally {
            ReflectionTestUtils.setField(target, "enabled", false);
        }
    }
}
