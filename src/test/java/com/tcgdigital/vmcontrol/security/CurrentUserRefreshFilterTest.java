package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An open session follows the database on the very next request: demotion, promotion,
 * deactivation and the absolute session lifetime (E03-T09, H10).
 *
 * <p>{@code as(user)} captures the user's roles when the request is built, like a session
 * created at login; the database is then changed before the request runs.
 */
class CurrentUserRefreshFilterTest extends SecuredWebTestBase {

    private User save(User user) {
        return userRepository.save(user);
    }

    @Test
    void demotedAdminLosesAdminRightsOnTheNextRequest() throws Exception {
        RequestPostProcessor sessionAsAdmin = as(admin);
        mockMvc.perform(get("/api/v1/users").with(sessionAsAdmin)).andExpect(status().isOk());

        admin.setAdmin(false);
        save(admin);

        expectError(mockMvc.perform(get("/api/v1/users").with(sessionAsAdmin)), 403);
    }

    @Test
    void promotedUserGetsAdminRightsWithoutSigningInAgain() throws Exception {
        RequestPostProcessor sessionAsUser = as(operator);
        expectError(mockMvc.perform(get("/api/v1/users").with(sessionAsUser)), 403);

        operator.setAdmin(true);
        save(operator);

        mockMvc.perform(get("/api/v1/users").with(sessionAsUser)).andExpect(status().isOk());
    }

    @Test
    void deactivatedUserIsSignedOutAndTheSessionInvalidated() throws Exception {
        RequestPostProcessor sessionAsUser = as(operator);
        MockHttpSession session = new MockHttpSession();

        operator.setIsActive(false);
        save(operator);

        mockMvc.perform(get("/api/v1/users/me").session(session).with(sessionAsUser))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has ended. Sign in again."));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void deactivatedUserOnAPageIsRedirectedToLogin() throws Exception {
        RequestPostProcessor sessionAsUser = as(operator);
        operator.setIsActive(false);
        save(operator);

        mockMvc.perform(get("/home").with(sessionAsUser))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/login?expired"));
    }

    @Test
    void sessionOlderThanTheAbsoluteLifetimeIsRejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(CurrentUserRefreshFilter.ISSUED_AT,
                System.currentTimeMillis() - Duration.ofHours(13).toMillis());

        expectError(mockMvc.perform(get("/api/v1/users/me").session(session).with(asUser())), 401);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void freshSessionGetsAnIssuedAtAndKeepsWorking() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/api/v1/users/me").session(session).with(asUser())).andExpect(status().isOk());

        assertThat(session.getAttribute(CurrentUserRefreshFilter.ISSUED_AT)).isInstanceOf(Long.class);
    }
}
