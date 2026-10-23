package com.tcgdigital.vmcontrol.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** U20-T12: Sign out ends the app session only, unless entraid.logout.end-microsoft-session is on. */
class EntraLogoutSuccessHandlerTest {

    private static final String END_SESSION = "https://login.microsoftonline.com/tenant/oauth2/v2.0/logout";

    private static OAuth2AuthenticationToken entraUser() {
        OidcIdToken token = new OidcIdToken("id-token-value", Instant.now(), Instant.now().plusSeconds(600),
                Map.of("sub", "s1", "name", "Asha Rao"));
        DefaultOidcUser user = new DefaultOidcUser(List.of(), token, "name");
        return new OAuth2AuthenticationToken(user, List.of(), "azure");
    }

    private static String logout(EntraLogoutSuccessHandler handler, org.springframework.security.core.Authentication who) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onLogoutSuccess(new MockHttpServletRequest("POST", "/logout"), response, who);
        return response.getRedirectedUrl();
    }

    @Test
    void byDefaultAMicrosoftUserGoesToTheSignInPage() throws Exception {
        assertThat(logout(new EntraLogoutSuccessHandler(END_SESSION, "", false), entraUser())).isEqualTo("/login?logout=true");
    }

    @Test
    void withTheSwitchOnTheMicrosoftSessionEndsToo() throws Exception {
        String to = logout(new EntraLogoutSuccessHandler(END_SESSION, "https://stage.example/login?logout=true", true), entraUser());
        assertThat(to).startsWith(END_SESSION + "?")
                .contains("post_logout_redirect_uri=https://stage.example/login?logout%3Dtrue")
                .contains("id_token_hint=id-token-value");
    }

    @Test
    void aPasswordSessionAlwaysGoesToTheSignInPage() throws Exception {
        var password = UsernamePasswordAuthenticationToken.authenticated("u1", null, List.of());
        assertThat(logout(new EntraLogoutSuccessHandler(END_SESSION, "", true), password)).isEqualTo("/login?logout=true");
    }
}
