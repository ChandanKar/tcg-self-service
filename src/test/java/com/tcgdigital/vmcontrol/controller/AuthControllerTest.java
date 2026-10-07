package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.LoginRequest;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.AuthenticationService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Password sign-in and admin password reset under the production (Entra ID) security chain.
 */
class AuthControllerTest extends SecuredWebTestBase {

    private static final String GOOD_PASSWORD = "correct-horse-battery";

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void bcryptPasswordLogsIn() throws Exception {
        withPassword("hashed.user", passwordEncoder.encode(GOOD_PASSWORD));

        mockMvc.perform(login("hashed.user", GOOD_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.user.password").doesNotExist());
    }

    @Test
    void plainTextStoredPasswordIsRejected() throws Exception {
        withPassword("plain.user", GOOD_PASSWORD);

        mockMvc.perform(login("plain.user", GOOD_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void adminSetsPasswordThenUserCanLogIn() throws Exception {
        User target = withPassword("reset.user", null);

        mockMvc.perform(put("/api/v1/users/" + target.getUserId() + "/password").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + GOOD_PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(login("reset.user", GOOD_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void nonAdminCannotSetPassword() throws Exception {
        User target = withPassword("reset.user", null);

        expectError(mockMvc.perform(put("/api/v1/users/" + target.getUserId() + "/password").with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + GOOD_PASSWORD + "\"}")), 403);
    }

    @Test
    void shortPasswordIs400() throws Exception {
        User target = withPassword("reset.user", null);

        expectError(mockMvc.perform(put("/api/v1/users/" + target.getUserId() + "/password").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"short\"}")), 400);
    }

    @Test
    void loginIs404WhenPasswordLoginDisabled() {
        AuthenticationService service = mock(AuthenticationService.class);
        when(service.isPasswordLoginEnabled()).thenReturn(false);

        var result = new AuthController(service).login(new LoginRequest("any", GOOD_PASSWORD),
                new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service).isPasswordLoginEnabled();
        verifyNoMoreInteractions(service);
    }

    private static org.springframework.test.web.servlet.RequestBuilder login(String username, String password) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    private User withPassword(String username, String storedPassword) {
        User user = newUser(username + "@example.com", false, false);
        user.setUsername(username);
        user.setPassword(storedPassword);
        return userRepository.save(user);
    }
}
