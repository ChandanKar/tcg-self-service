package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.security.CurrentUserRefreshFilter;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U25-T04: an administrator resets a person's password. The server generates it and shows it once,
 * the person must choose their own at the next sign-in, every web session of theirs that started
 * before ends, and the audit log says "Password reset" without the password. PUT /password (kept
 * for API callers) now also forces the change and ends the sessions. Administrators only.
 */
class UserPasswordResetIntegrationTest extends SecuredWebTestBase {

    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private AuditLogRepository auditLogRepository;

    private static String resetUrl(User user) {
        return "/api/v1/users/" + user.getUserId() + "/password/reset";
    }

    /** A session of {@code user} that started a minute ago, as the browser keeps it. */
    private static MockHttpSession sessionStartedAMinuteAgo() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CurrentUserRefreshFilter.ISSUED_AT, System.currentTimeMillis() - 60_000);
        return session;
    }

    @Test
    void resetGeneratesAPasswordShownOnceThatMustBeChangedAndIsAudited() throws Exception {
        String json = mockMvc.perform(post(resetUrl(operator)).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.username").value("operator@secured.test"))
                .andReturn().getResponse().getContentAsString();
        String password = new ObjectMapper().readTree(json).get("password").asText();

        assertThat(password).matches("[ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789\\-_.!]{16}");
        User saved = userRepository.findById(operator.getUserId()).orElseThrow();
        assertThat(saved.getUsername()).as("their email becomes their username").isEqualTo("operator@secured.test");
        assertThat(saved.getPassword()).startsWith("$2").isNotEqualTo(password);
        assertThat(passwordEncoder.matches(password, saved.getPassword())).isTrue();
        assertThat(saved.isPasswordChangeRequired()).isTrue();
        awaitAsync(() -> assertThat(auditLogRepository.findTop100ByOrderByCreatedAtDesc())
                .anyMatch(l -> l.getAction() == AuditAction.USER_UPDATED
                        && operator.getUserId().equals(l.getTargetId())
                        && l.getDetails().startsWith("Password reset by user"))
                .noneMatch(l -> l.getDetails() != null && l.getDetails().contains(password)));
    }

    @Test
    void eachResetGivesANewPasswordAndForcesTheChangeAgain() throws Exception {
        String first = mockMvc.perform(post(resetUrl(operator)).with(asAdmin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        User saved = userRepository.findById(operator.getUserId()).orElseThrow();
        saved.setPasswordChangeRequired(false); // as if they had chosen their own
        userRepository.saveAndFlush(saved);

        String second = mockMvc.perform(post(resetUrl(operator)).with(asAdmin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode a = new ObjectMapper().readTree(first);
        JsonNode b = new ObjectMapper().readTree(second);
        assertThat(b.get("password").asText()).isNotEqualTo(a.get("password").asText());
        assertThat(userRepository.findById(operator.getUserId()).orElseThrow().isPasswordChangeRequired()).isTrue();
    }

    @Test
    void resetEndsThePersonsExistingSessions() throws Exception {
        MockHttpSession theirs = sessionStartedAMinuteAgo();
        mockMvc.perform(get("/api/v1/users/me").session(theirs).with(asUser())).andExpect(status().isOk());

        mockMvc.perform(post(resetUrl(operator)).with(asAdmin())).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me").session(theirs).with(asUser()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has ended. Sign in again."));
        assertThat(theirs.isInvalid()).isTrue();
    }

    @Test
    void someoneElsesSessionAndANewSessionKeepWorking() throws Exception {
        MockHttpSession adminSession = sessionStartedAMinuteAgo();
        mockMvc.perform(post(resetUrl(operator)).session(adminSession).with(asAdmin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users/me").session(adminSession).with(asAdmin())).andExpect(status().isOk());

        // A session that starts after the reset (their next sign-in) is not ended.
        MockHttpSession fresh = new MockHttpSession();
        fresh.setAttribute(CurrentUserRefreshFilter.ISSUED_AT, System.currentTimeMillis());
        mockMvc.perform(get("/api/v1/users/me").session(fresh).with(asUser())).andExpect(status().isOk());
    }

    @Test
    void setPasswordNowForcesTheChangeAndEndsSessionsToo() throws Exception {
        User legacy = userRepository.findById(viewer.getUserId()).orElseThrow();
        legacy.setUsername("legacy.viewer");
        legacy.setPassword(passwordEncoder.encode("old-password-123"));
        userRepository.saveAndFlush(legacy);
        MockHttpSession theirs = sessionStartedAMinuteAgo();

        mockMvc.perform(put("/api/v1/users/" + viewer.getUserId() + "/password").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"typed-by-admin-123\"}"))
                .andExpect(status().isNoContent());

        User saved = userRepository.findById(viewer.getUserId()).orElseThrow();
        assertThat(saved.getUsername()).isEqualTo("legacy.viewer");
        assertThat(saved.isPasswordChangeRequired()).as("the admin does not keep a working password").isTrue();
        expectError(mockMvc.perform(get("/api/v1/users/me").session(theirs).with(asViewer())), 401);
    }

    @Test
    void aServiceAccountOrSomeoneWithoutAnEmailCannotBeReset() throws Exception {
        User service = newUser("bot@secured.test", false, false);
        service.setServiceAccount(true);
        userRepository.saveAndFlush(service);
        expectError(mockMvc.perform(post(resetUrl(service)).with(asAdmin())), 400)
                .andExpect(jsonPath("$.message").value("A service account signs in with API tokens, not a password"));

        User noEmail = newUser("nomail@secured.test", false, false);
        noEmail.setEmail(" "); // the column is NOT NULL: blank is how "no email" looks
        userRepository.saveAndFlush(noEmail);
        expectError(mockMvc.perform(post(resetUrl(noEmail)).with(asAdmin())), 400)
                .andExpect(jsonPath("$.message").value("This person has no email address to sign in with"));
        assertThat(userRepository.findById(noEmail.getUserId()).orElseThrow().getPassword()).isNull();
    }

    @Test
    void onlyAdministratorsResetPasswords() throws Exception {
        expectError(mockMvc.perform(post(resetUrl(viewer)).with(asEnvAdmin())), 403);
        expectError(mockMvc.perform(post(resetUrl(viewer)).with(asUser())), 403);
        expectError(mockMvc.perform(post(resetUrl(operator)).with(asViewer())), 403);
        assertThat(userRepository.findById(viewer.getUserId()).orElseThrow().getPassword()).isNull();
    }

    @Test
    void anUnknownPersonIsNotFound() throws Exception {
        expectError(mockMvc.perform(post("/api/v1/users/no-such-user/password/reset").with(asAdmin())), 404);
    }
}
