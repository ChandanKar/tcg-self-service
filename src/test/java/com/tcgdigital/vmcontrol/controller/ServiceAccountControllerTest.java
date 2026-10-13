package com.tcgdigital.vmcontrol.controller;

import com.jayway.jsonpath.JsonPath;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.ApiTokenService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Service accounts (E34-T03): admins only, their tokens reach granted environments only, deactivation revokes. */
class ServiceAccountControllerTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/service-accounts";

    @Autowired private ApiTokenService tokenService;

    private void tokens(boolean on) {
        Object target = AopTestUtils.getTargetObject(tokenService);
        ReflectionTestUtils.setField(target, "enabled", on);
    }

    @BeforeEach
    void on() {
        tokens(true);
    }

    @AfterEach
    void off() {
        tokens(false);
    }

    private String createAccount(String name, String role) throws Exception {
        String json = mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"role\":\"" + role + "\",\"ownerUserId\":\"" + operator.getUserId() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(name + "@service.local"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.userId");
    }

    private String issue(String accountId, String scopes) throws Exception {
        String json = mockMvc.perform(post(URL + "/" + accountId + "/tokens").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pipeline\",\"scopes\":" + scopes + ",\"expiresInDays\":90}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.token");
    }

    @Test
    void aServiceAccountsTokenReachesOnlyItsGrantedEnvironment() throws Exception {
        String id = createAccount("ci-deployer", "USER");
        Environment granted = newEnvironment("Granted");
        Environment other = newEnvironment("Other");
        User account = userRepository.findById(id).orElseThrow();
        grantEnv(account, granted.getEnvironmentId(), AccessLevel.USER);
        String token = issue(id, "[\"environments:read\",\"operations:write\"]");

        mockMvc.perform(get("/api/v1/environments/" + granted.getEnvironmentId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        org.springframework.test.web.servlet.MvcResult denied = mockMvc.perform(get("/api/v1/environments/" + other.getEnvironmentId())
                .header("Authorization", "Bearer " + token)).andReturn();
        assertThat(denied.getResponse().getStatus()).as(denied.getResponse().getContentAsString()).isEqualTo(403);
        mockMvc.perform(get(URL + "/" + id + "/tokens").with(asAdmin()))
                .andExpect(jsonPath("$[0].kind").value("SERVICE"));
    }

    @Test
    void deactivatingRevokesItsTokens() throws Exception {
        String id = createAccount("nightly", "USER");
        Environment env = newEnvironment("Nightly");
        grantEnv(userRepository.findById(id).orElseThrow(), env.getEnvironmentId(), AccessLevel.USER);
        String token = issue(id, "[\"environments:read\"]");

        mockMvc.perform(delete(URL + "/" + id).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.activeTokens").value(0));
        mockMvc.perform(get("/api/v1/environments").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        expectError(mockMvc.perform(post(URL + "/" + id + "/tokens").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"again\",\"scopes\":[\"environments:read\"],\"expiresInDays\":1}")), 400);
    }

    @Test
    void onlyAdminsManageServiceAccountsAndNeverWithAToken() throws Exception {
        mockMvc.perform(get(URL).with(asEnvAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x1\",\"role\":\"USER\",\"ownerUserId\":\"" + operator.getUserId() + "\"}")).andExpect(status().isForbidden());

        String adminToken = tokenService.create(admin,
                new com.tcgdigital.vmcontrol.dto.CreateApiTokenDTO("t", java.util.List.of("admin"), 1)).token();
        mockMvc.perform(get(URL).header("Authorization", "Bearer " + adminToken)).andExpect(status().isForbidden());
    }

    @Test
    void anAdminRoleBadNamesAndMissingOwnersAreRefused() throws Exception {
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"root-bot\",\"role\":\"ADMIN\",\"ownerUserId\":\"" + operator.getUserId() + "\"}")), 400)
                .andExpect(jsonPath("$.message", containsString("USER or ENV_ADMIN")));
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bad Name\",\"role\":\"USER\",\"ownerUserId\":\"" + operator.getUserId() + "\"}")), 400);
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"orphan\",\"role\":\"USER\",\"ownerUserId\":\"nobody\"}")), 400);
        String id = createAccount("dup-bot", "ENV_ADMIN");
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"dup-bot\",\"role\":\"USER\",\"ownerUserId\":\"" + operator.getUserId() + "\"}")), 400);
        // An env-admin service account still cannot hold the admin scope.
        expectError(mockMvc.perform(post(URL + "/" + id + "/tokens").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"t\",\"scopes\":[\"admin\"],\"expiresInDays\":1}")), 400);
        mockMvc.perform(get("/api/v1/users").with(asAdmin()))
                .andExpect(jsonPath("$[?(@.userId == '" + id + "')].serviceAccount").value(true));
    }

    @Test
    void switchedOffServiceAccountsAreNotFound() throws Exception {
        tokens(false);
        expectError(mockMvc.perform(get(URL).with(asAdmin())), 404);
        assertThat(tokenService.isEnabled()).isFalse();
    }
}
