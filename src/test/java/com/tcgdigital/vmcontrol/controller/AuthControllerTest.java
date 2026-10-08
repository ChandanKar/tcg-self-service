package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.LoginRequest;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.AuthenticationService;
import com.tcgdigital.vmcontrol.service.LoginThrottleService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

        var result = new AuthController(service, mock(LoginThrottleService.class)).login(new LoginRequest("any", GOOD_PASSWORD),
                new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(service).isPasswordLoginEnabled();
        verifyNoMoreInteractions(service);
    }

    @Test
    void sixthFailedAttemptIsThrottledEvenWithTheRightPassword() throws Exception {
        withPassword("throttled.user", passwordEncoder.encode(GOOD_PASSWORD));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(login("throttled.user", "wrong-password-" + i)).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(login("throttled.user", GOOD_PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void loginRotatesTheSessionIdAndKeepsNoUserEntityInTheSession() throws Exception {
        User user = withPassword("session.user", passwordEncoder.encode(GOOD_PASSWORD));
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();

        MvcResult result = mockMvc.perform(login("session.user", GOOD_PASSWORD).session(session))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession after = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(after).isNotNull();
        assertThat(after.getId()).isNotEqualTo(before);
        var names = java.util.Collections.list(after.getAttributeNames());
        assertThat(names).doesNotContain("userId", "username", "userEmail");
        for (String name : names) {
            assertThat(after.getAttribute(name).toString()).doesNotContain(user.getPassword());
        }

        mockMvc.perform(get("/api/v1/users/me").session(after))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()));
    }

    @Test
    void optionsAreAnonymousAndOfferPasswordLoginByDefault() throws Exception {
        mockMvc.perform(get("/api/auth/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordLoginEnabled").value(true));
    }

    @Test
    void optionsReportPasswordLoginDisabled() {
        AuthenticationService service = mock(AuthenticationService.class);
        when(service.isPasswordLoginEnabled()).thenReturn(false);

        assertThat(new AuthController(service, mock(LoginThrottleService.class)).options())
                .containsEntry("passwordLoginEnabled", false);
    }

    @Test
    void userListShowsPasswordStatusButNeverTheHash() throws Exception {
        User user = withPassword("listed.user", passwordEncoder.encode(GOOD_PASSWORD));

        mockMvc.perform(get("/api/v1/users/" + user.getUserId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("listed.user"))
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /** Each request gets its own client IP so the shared per-IP counter never leaks between tests. */
    private static MockHttpServletRequestBuilder login(String username, String password) {
        String ip = "10.99." + (UUID.randomUUID().hashCode() & 0xff) + "." + (UUID.randomUUID().hashCode() & 0xff);
        return post("/api/auth/login")
                .with(csrf()) // login.js sends the XSRF-TOKEN cookie back as X-XSRF-TOKEN
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
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
