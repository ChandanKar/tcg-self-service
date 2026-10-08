package com.tcgdigital.vmcontrol.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * After the app session is cleared, sends an Entra-signed-in user to the tenant's end-session
 * endpoint so the Microsoft session ends too and the next "Sign in with Entra ID" prompts again.
 * Password sessions have no Entra session and go straight back to the login page.
 *
 * Spring's OidcClientInitiatedLogoutSuccessHandler is not used: the azure provider is configured
 * with explicit URIs (no issuer-uri discovery), so it never learns the end_session_endpoint.
 */
public class EntraLogoutSuccessHandler implements LogoutSuccessHandler {

    static final String LOGIN_AFTER_LOGOUT = "/login?logout=true";

    private final String endSessionUri;
    private final String postLogoutRedirectUri;

    /**
     * @param endSessionUri         the tenant's OIDC logout endpoint (blank: local logout only)
     * @param postLogoutRedirectUri where Entra returns the browser; blank derives it from the request.
     *                              Must be registered on the Entra app registration.
     */
    public EntraLogoutSuccessHandler(String endSessionUri, String postLogoutRedirectUri) {
        this.endSessionUri = endSessionUri;
        this.postLogoutRedirectUri = postLogoutRedirectUri;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                Authentication authentication) throws IOException {
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser
                && StringUtils.hasText(endSessionUri)) {
            UriComponentsBuilder target = UriComponentsBuilder.fromUriString(endSessionUri)
                    .queryParam("post_logout_redirect_uri", postLogoutRedirect(request));
            if (oidcUser.getIdToken() != null) {
                target.queryParam("id_token_hint", oidcUser.getIdToken().getTokenValue());
            }
            response.sendRedirect(target.encode().build().toUriString());
            return;
        }
        response.sendRedirect(request.getContextPath() + LOGIN_AFTER_LOGOUT);
    }

    private String postLogoutRedirect(HttpServletRequest request) {
        if (StringUtils.hasText(postLogoutRedirectUri)) {
            return postLogoutRedirectUri;
        }
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        return UriComponentsBuilder.newInstance()
                .scheme(request.getScheme())
                .host(request.getServerName())
                .port(defaultPort ? -1 : port)
                .path(request.getContextPath() + "/login")
                .query("logout=true")
                .toUriString();
    }
}
