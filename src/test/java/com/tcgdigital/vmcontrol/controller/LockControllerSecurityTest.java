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

    @Test
    void inactiveEnvironmentCannotBeLocked() throws Exception {
        env.setIsActive(false);
        environmentRepository.save(env);

        expectError(mockMvc.perform(acquire(asAdmin())), 400);
        assertThat(locked()).isFalse();
    }
}
