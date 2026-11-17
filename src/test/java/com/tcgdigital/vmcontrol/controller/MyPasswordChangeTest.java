package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U29: a person changes their own password from My account, and an administrator can type one for
 * someone who has none yet (imported people), under the production (Entra ID) security chain.
 */
class MyPasswordChangeTest extends SecuredWebTestBase {

    private static final String CURRENT = "my-current-password";
    private static final String NEW = "my-brand-new-password";

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ResultActions change(User who, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/v1/users/me/password").with(as(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}"));
    }

    private User withPassword(User user, String password) {
        user.setUsername(user.getEmail());
        user.setPassword(passwordEncoder.encode(password));
        user.setPasswordChangeRequired(true);
        return userRepository.save(user);
    }

    @Test
    void aPersonWithAPasswordChangesItAndTheChangeIsAudited() throws Exception {
        withPassword(operator, CURRENT);

        change(operator, CURRENT, NEW).andExpect(status().isNoContent());

        User saved = userRepository.findById(operator.getUserId()).orElseThrow();
        assertThat(passwordEncoder.matches(NEW, saved.getPassword())).isTrue();
        assertThat(saved.isPasswordChangeRequired()).isFalse();
        assertThat(saved.getPasswordUpdatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target_id = ? AND details LIKE ?",
                Integer.class, operator.getUserId(), "Password changed by the person themselves%")).isEqualTo(1);
    }

    @Test
    void aWrongCurrentPasswordIs400NotASignOut() throws Exception {
        withPassword(operator, CURRENT);

        expectError(change(operator, "not-the-password", NEW), 400)
                .andExpect(jsonPath("$.message").value("The current password is not right."));

        assertThat(passwordEncoder.matches(CURRENT, userRepository.findById(operator.getUserId()).orElseThrow().getPassword())).isTrue();
    }

    @Test
    void someoneWhoSignsInWithMicrosoftIsToldToChangeItThere() throws Exception {
        expectError(change(operator, CURRENT, NEW), 400)
                .andExpect(jsonPath("$.message").value("You sign in with Microsoft. Change your password there."));
    }

    @Test
    void theNewPasswordMustBeLongEnoughAndDifferent() throws Exception {
        withPassword(operator, CURRENT);

        expectError(change(operator, CURRENT, "short"), 400);
        expectError(change(operator, CURRENT, CURRENT), 400)
                .andExpect(jsonPath("$.message").value("Choose a password other than your current one."));
    }

    @Test
    void repeatedWrongAttemptsAreThrottled() throws Exception {
        withPassword(viewer, CURRENT);
        for (int i = 0; i < 5; i++) {
            change(viewer, "wrong-" + i + "-password", NEW).andExpect(status().isBadRequest());
        }

        change(viewer, CURRENT, NEW).andExpect(status().isTooManyRequests());
    }

    @Test
    void withoutACsrfTokenItIsRefused() throws Exception {
        withPassword(operator, CURRENT);

        mockMvc.perform(post("/api/v1/users/me/password").with(asWithoutCsrf(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + CURRENT + "\",\"newPassword\":\"" + NEW + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void myProfileOffersTheChangeOnlyToPeopleWithAPassword() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/profile").with(as(operator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canChangePassword").value(false));

        withPassword(operator, CURRENT);

        mockMvc.perform(get("/api/v1/users/me/profile").with(as(operator)))
                .andExpect(jsonPath("$.canChangePassword").value(true));
    }

    /** Imported people have neither a password nor a username: an administrator can type one for them. */
    @Test
    void anAdministratorTypesAPasswordForSomeoneWhoHasNone() throws Exception {
        User imported = newUser("Imported.Person@Example.com", false, false);
        assertThat(imported.getUsername()).isNull();

        mockMvc.perform(put("/api/v1/users/" + imported.getUserId() + "/password").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + NEW + "\"}"))
                .andExpect(status().isNoContent());

        User saved = userRepository.findById(imported.getUserId()).orElseThrow();
        assertThat(saved.getUsername()).isEqualTo("imported.person@example.com");
        assertThat(saved.isPasswordChangeRequired()).isTrue();
        assertThat(passwordEncoder.matches(NEW, saved.getPassword())).isTrue();
    }
}
