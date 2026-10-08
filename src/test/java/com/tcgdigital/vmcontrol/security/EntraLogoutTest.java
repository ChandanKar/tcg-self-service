package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Logout ends the Entra session too: POST /logout (with CSRF token) sends Entra users to the
 * tenant end-session endpoint; GET /logout no longer logs anyone out (E03-T12).
 */
class EntraLogoutTest extends SecuredWebTestBase {

    @Test
    void entraUserIsSentToTheEntraEndSessionEndpoint() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/logout").with(asUser()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", startsWith("https://login.microsoftonline.com/")))
                .andExpect(header().string("Location", containsString("/oauth2/v2.0/logout?")))
                .andExpect(header().string("Location",
                        containsString("post_logout_redirect_uri=http://localhost/login?logout%3Dtrue")))
                .andExpect(header().string("Location", containsString("id_token_hint=")));

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void passwordSessionLogsOutLocally() throws Exception {
        UsernamePasswordAuthenticationToken passwordLogin = UsernamePasswordAuthenticationToken.authenticated(
                operator.getUserId(), null, List.of());

        mockMvc.perform(post("/logout").with(authentication(passwordLogin)).with(csrf()))
                .andExpect(redirectedUrl("/login?logout=true"));
    }

    @Test
    void logoutWithoutCsrfTokenIsRefused() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/logout").with(asWithoutCsrf(operator)).session(session))
                .andExpect(status().isForbidden());

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void getLogoutDoesNotEndTheSession() throws Exception {
        MockHttpSession session = new MockHttpSession();

        MvcResult result = mockMvc.perform(get("/logout").with(asUser()).session(session)).andReturn();

        assertThat(result.getResponse().getStatus()).isIn(404, 405);
        assertThat(session.isInvalid()).isFalse();
    }
}
