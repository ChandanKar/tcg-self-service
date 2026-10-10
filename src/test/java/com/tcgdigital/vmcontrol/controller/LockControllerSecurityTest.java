package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.EnvironmentLockRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lock endpoints under the production (Entra ID) chain: view rights to read, operate rights to
 * acquire, an admin role plus administer rights to break (E03-T02, H3).
 */
class LockControllerSecurityTest extends SecuredWebTestBase {

    @Autowired
    private EnvironmentLockRepository lockRepository;

    private Environment env;
    private VmGroup group;

    @BeforeEach
    void setUpEnvironment() {
        env = newEnvironment("Locked");
        group = newGroup(env, "web");
    }

    private String url(String suffix) {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/lock" + suffix;
    }

    private MockHttpServletRequestBuilder acquire(RequestPostProcessor who) {
        return post(url("/acquire")).with(who).contentType(MediaType.APPLICATION_JSON).content("{}");
    }

    private MockHttpServletRequestBuilder breakLock(RequestPostProcessor who) {
        return post(url("/break")).with(who).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"maintenance\"}");
    }

    private boolean locked() {
        return lockRepository.findByEnvironmentEnvironmentIdAndIsActiveTrue(env.getEnvironmentId()).isPresent();
    }

    @Test
    void userWithoutAnyGrantCannotReadOrAcquire() throws Exception {
        expectError(mockMvc.perform(get(url("")).with(asViewer())), 403);
        expectError(mockMvc.perform(get(url("/history")).with(asViewer())), 403);
        expectError(mockMvc.perform(acquire(asViewer())), 403);
        assertThat(locked()).isFalse();
    }

    @Test
    void viewerCanReadButNotAcquire() throws Exception {
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);

        mockMvc.perform(get(url("")).with(asViewer())).andExpect(status().isOk());
        mockMvc.perform(get(url("/history")).with(asViewer())).andExpect(status().isOk());
        expectError(mockMvc.perform(acquire(asViewer())), 403);
        assertThat(locked()).isFalse();
    }

    @Test
    void environmentUserCanAcquire() throws Exception {
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);

        mockMvc.perform(acquire(asUser())).andExpect(status().isOk());
        assertThat(locked()).isTrue();
    }

    @Test
    void groupUserCanAcquireTheEnvironmentLock() throws Exception {
        grantGroup(operator, group.getGroupId(), AccessLevel.USER);

        mockMvc.perform(acquire(asUser())).andExpect(status().isOk());
        assertThat(locked()).isTrue();
    }

    @Test
    void environmentAdminGranteeWithoutAdminRoleCannotBreak() throws Exception {
        User grantee = newUser("env-admin-grantee@secured.test", false, false);
        grantEnv(grantee, env.getEnvironmentId(), AccessLevel.ADMIN);
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        mockMvc.perform(acquire(asUser())).andExpect(status().isOk());

        // The role gate on /break stays: an ADMIN grant alone is not enough.
        expectError(mockMvc.perform(breakLock(as(grantee))), 403);
        assertThat(locked()).isTrue();
    }

    @Test
    void adminCanBreak() throws Exception {
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        mockMvc.perform(acquire(asUser())).andExpect(status().isOk());

        mockMvc.perform(breakLock(asAdmin())).andExpect(status().isOk());
        assertThat(locked()).isFalse();
    }

    private MockHttpServletRequestBuilder extend(RequestPostProcessor who, String body) {
        return post(url("/extend")).with(who).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void onlyTheHolderCanExtendTheLock() throws Exception {
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
        User colleague = newUser("extend-colleague@secured.test", false, false);
        grantEnv(colleague, env.getEnvironmentId(), AccessLevel.USER);
        mockMvc.perform(acquire(asUser()).content("{\"expectedDurationMinutes\":30}")).andExpect(status().isOk());

        mockMvc.perform(extend(asUser(), "{\"minutes\":30}")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.expiresAt").exists());
        expectError(mockMvc.perform(extend(as(colleague), "{\"minutes\":30}")), 403);
        expectError(mockMvc.perform(extend(asViewer(), "{\"minutes\":30}")), 403);
        expectError(mockMvc.perform(extend(asUser(), "{\"minutes\":0}")), 400);
        expectError(mockMvc.perform(extend(asUser(), "{\"minutes\":481}")), 400);
        expectError(mockMvc.perform(extend(asUser(), "{}")), 400);
    }

    @Test
    void extendingWithoutALockIsABadRequest() throws Exception {
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);

        expectError(mockMvc.perform(extend(asUser(), "{\"minutes\":30}")), 400);
    }

    private org.springframework.test.web.servlet.ResultActions lockStatus(RequestPostProcessor who) throws Exception {
        return mockMvc.perform(get(url("")).with(who)).andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.ResultMatcher flags(boolean acquire, boolean release,
                                                                          boolean extend, boolean breakIt) {
        return result -> {
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canAcquire").value(acquire).match(result);
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canRelease").value(release).match(result);
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canExtend").value(extend).match(result);
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.canBreak").value(breakIt).match(result);
        };
    }

    @Test
    void anUnlockedEnvironmentOffersAcquireOnlyToThoseWhoCanOperate() throws Exception {
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);

        lockStatus(asViewer()).andExpect(flags(false, false, false, false));
        lockStatus(asUser()).andExpect(flags(true, false, false, false));
        lockStatus(asAdmin()).andExpect(flags(true, false, false, false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.lockExpiryEnabled").value(false));
    }

    @Test
    void aLockedEnvironmentOffersReleaseAndExtendToTheHolderAndBreakToAdmins() throws Exception {
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        User colleague = newUser("flags-colleague@secured.test", false, false);
        grantEnv(colleague, env.getEnvironmentId(), AccessLevel.USER);
        mockMvc.perform(acquire(asUser()).content("{\"expectedDurationMinutes\":30}")).andExpect(status().isOk())
                .andExpect(flags(false, true, true, false));

        lockStatus(asUser()).andExpect(flags(false, true, true, false));
        lockStatus(as(colleague)).andExpect(flags(false, false, false, false));
        lockStatus(asViewer()).andExpect(flags(false, false, false, false));
        lockStatus(asEnvAdmin()).andExpect(flags(false, false, false, true));
        lockStatus(asAdmin()).andExpect(flags(false, false, false, true));
    }

    @Test
    void anOpenEndedLockCannotBeExtended() throws Exception {
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        mockMvc.perform(acquire(asUser())).andExpect(status().isOk());

        lockStatus(asUser()).andExpect(flags(false, true, false, false));
    }

    @Test
    void historyRowsCarryTheirOwnTimeAndReason() throws Exception {
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        mockMvc.perform(acquire(asUser()).content("{\"reason\":\"deploy\",\"expectedDurationMinutes\":30}"))
                .andExpect(status().isOk());
        mockMvc.perform(extend(asUser(), "{\"minutes\":30}")).andExpect(status().isOk());
        mockMvc.perform(post(url("/break")).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"stuck deploy\"}")).andExpect(status().isOk());

        String body = mockMvc.perform(get(url("/history")).with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode rows = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> byAction = new java.util.HashMap<>();
        rows.forEach(r -> byAction.put(r.get("action").asText(), r));

        assertThat(byAction.get("ACQUIRED").get("reason").asText()).isEqualTo("deploy");
        assertThat(byAction.get("EXTENDED").get("reason").asText()).startsWith("Extended by 30 minutes");
        assertThat(byAction.get("BROKEN").get("reason").asText()).isEqualTo("stuck deploy");
        assertThat(byAction.get("BROKEN").get("breakReason").asText()).isEqualTo("stuck deploy");
        // Each row's timestamp is its own performedAt, not the lock's acquisition time.
        rows.forEach(r -> assertThat(r.get("timestamp")).isEqualTo(r.get("performedAt")));
    }

    @Test
    void inactiveEnvironmentCannotBeLocked() throws Exception {
        env.setIsActive(false);
        environmentRepository.save(env);

        expectError(mockMvc.perform(acquire(asAdmin())), 400);
        assertThat(locked()).isFalse();
    }
}
