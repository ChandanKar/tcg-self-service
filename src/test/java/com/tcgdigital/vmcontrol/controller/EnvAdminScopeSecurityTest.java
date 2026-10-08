package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.equalTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Environment-specific admin actions follow security.env-admin.scope (E03-T05): with 'assigned'
 * an ENV_ADMIN needs an ENVIRONMENT ADMIN grant; with 'global' (default) nothing changes.
 */
class EnvAdminScopeSecurityTest extends SecuredWebTestBase {

    @Autowired
    private SecurityService securityService;

    @Autowired
    private EnvironmentAccessService accessService;

    private Object originalScope;
    private Environment envA;
    private Environment envB;

    @BeforeEach
    void setUpEnvironments() {
        originalScope = ReflectionTestUtils.getField(securityService, "envAdminScope");
        envA = newEnvironment("Env A");
        envB = newEnvironment("Env B");
    }

    @AfterEach
    void restoreScope() {
        ReflectionTestUtils.setField(securityService, "envAdminScope", originalScope);
    }

    private void scope(String value) {
        ReflectionTestUtils.setField(securityService, "envAdminScope", value);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String a(String suffix) {
        return "/api/v1/environments/" + envA.getEnvironmentId() + suffix;
    }

    private EnvironmentAccessRequest pendingRequest(Environment env) {
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO();
        dto.setAccessLevel(AccessLevel.USER);
        dto.setBusinessJustification("Need to operate " + env.getName());
        return accessService.createAccessRequest(env.getEnvironmentId(), operator.getUserId(), dto);
    }

    private String ruleBody(Environment env) {
        return "{\"environmentId\":\"" + env.getEnvironmentId() + "\",\"name\":\"Nightly stop\","
                + "\"scopeType\":\"ENVIRONMENT\",\"triggerType\":\"SCHEDULE\",\"daysOfWeek\":[\"MON\"],"
                + "\"stopTime\":\"20:00\",\"timezone\":\"UTC\"}";
    }

    @Test
    void assignedScopeWithoutAGrantRefusesEveryEnvironmentMutation() throws Exception {
        scope("assigned");
        EnvironmentAccessRequest request = pendingRequest(envA);

        expectError(mockMvc.perform(json(put(a("")).with(asEnvAdmin()), "{\"displayName\":\"Renamed\"}")), 403);
        expectError(mockMvc.perform(json(post(a("/access")).with(asEnvAdmin()),
                "{\"userEmail\":\"" + operator.getEmail() + "\",\"accessLevel\":\"USER\",\"durationDays\":7}")), 403);
        expectError(mockMvc.perform(get(a("/access")).with(asEnvAdmin())), 403);
        expectError(mockMvc.perform(json(post("/api/v1/access-requests/" + request.getRequestId() + "/approve")
                .with(asEnvAdmin()), "{}")), 403);
        expectError(mockMvc.perform(json(post("/api/v1/automation-rules").with(asEnvAdmin()), ruleBody(envA))), 403);
        expectError(mockMvc.perform(post("/api/v1/monitoring/sync/environment/" + envA.getEnvironmentId())
                .with(asEnvAdmin())), 403);
    }

    @Test
    void assignedScopeWithAnAdminGrantAllowsThoseActions() throws Exception {
        scope("assigned");
        grantEnv(envAdmin, envA.getEnvironmentId(), AccessLevel.ADMIN);
        EnvironmentAccessRequest request = pendingRequest(envA);

        mockMvc.perform(json(put(a("")).with(asEnvAdmin()), "{\"displayName\":\"Renamed\"}")).andExpect(status().isOk());
        mockMvc.perform(get(a("/access")).with(asEnvAdmin())).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/v1/access-requests/" + request.getRequestId() + "/approve")
                .with(asEnvAdmin()), "{}")).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/v1/automation-rules").with(asEnvAdmin()), ruleBody(envA))).andExpect(status().isOk());
    }

    @Test
    void assignedScopePendingListOnlyShowsAdministeredEnvironments() throws Exception {
        scope("assigned");
        grantEnv(envAdmin, envA.getEnvironmentId(), AccessLevel.ADMIN);
        pendingRequest(envA);
        pendingRequest(envB);

        mockMvc.perform(get("/api/v1/access-requests/pending").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].environmentId", everyItem(equalTo(envA.getEnvironmentId()))));
    }

    @Test
    void envAdminWhoCreatesAnEnvironmentAdministersIt() throws Exception {
        scope("assigned");
        String body = "{\"name\":\"created-by-env-admin\",\"displayName\":\"Created by env admin\",\"cloudProvider\":\"AWS\"}";
        String json = mockMvc.perform(json(post("/api/v1/environments").with(asEnvAdmin()), body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String createdId = com.jayway.jsonpath.JsonPath.read(json, "$.environmentId");

        assertThat(accessService.getActiveGrant(envAdmin.getUserId(), AccessScopeType.ENVIRONMENT, createdId))
                .hasValueSatisfying(grant -> assertThat(grant.getAccessLevel()).isEqualTo(AccessLevel.ADMIN));
        mockMvc.perform(json(put("/api/v1/environments/" + createdId).with(asEnvAdmin()), "{\"displayName\":\"Mine\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void globalScopeKeepsTodaysBehaviour() throws Exception {
        scope("global");
        EnvironmentAccessRequest request = pendingRequest(envB);

        mockMvc.perform(json(put(a("")).with(asEnvAdmin()), "{\"displayName\":\"Renamed\"}")).andExpect(status().isOk());
        mockMvc.perform(json(post("/api/v1/access-requests/" + request.getRequestId() + "/deny")
                .with(asEnvAdmin()), "{\"notes\":\"no\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/monitoring/sync/environment/" + envA.getEnvironmentId()).with(asEnvAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void plainUserWithAnAdminGrantStillNeedsTheRoleForRoleGatedEndpoints() throws Exception {
        grantEnv(operator, envA.getEnvironmentId(), AccessLevel.ADMIN);

        expectError(mockMvc.perform(json(put(a("")).with(asUser()), "{\"displayName\":\"Renamed\"}")), 403);
    }
}
