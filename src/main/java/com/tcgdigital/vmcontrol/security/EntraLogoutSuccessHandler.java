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
 * After the app session is cleared, everyone goes to the sign-in page, "You are signed out"
 * (U20-T12, owner's choice): signing out of the app leaves the person signed in to Microsoft
 * (Outlook, Teams…), so "Sign in with Microsoft" may sign them straight back in.
 *
 * With entraid.logout.end-microsoft-session on, an Entra-signed-in user is sent to the tenant's
 * end-session endpoint instead, so the Microsoft session ends too; Entra then returns the browser to
 * entraid.post-logout-redirect-uri. Password sessions always go straight back to the login page.
 *
 * Spring's OidcClientInitiatedLogoutSuccessHandler is not used: the azure provider is configured
 * with explicit URIs (no issuer-uri discovery), so it never learns the end_session_endpoint.
 */
public class EntraLogoutSuccessHandler implements LogoutSuccessHandler {

    static final String LOGIN_AFTER_LOGOUT = "/login?logout=true";

    private final String endSessionUri;
    private final String postLogoutRedirectUri;
    private final boolean endMicrosoftSession;

    /**
     * @param endSessionUri         the tenant's OIDC logout endpoint (blank: local logout only)
     * @param postLogoutRedirectUri where Entra returns the browser; blank derives it from the request.
     *                              Must be registered on the Entra app registration.
     * @param endMicrosoftSession   also end the Microsoft session (off: only the app's)
     */
    public EntraLogoutSuccessHandler(String endSessionUri, String postLogoutRedirectUri, boolean endMicrosoftSession) {
        this.endSessionUri = endSessionUri;
        this.postLogoutRedirectUri = postLogoutRedirectUri;
        this.endMicrosoftSession = endMicrosoftSession;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
                                Authentication authentication) throws IOException {
        if (endMicrosoftSession && authentication != null && authentication.getPrincipal() instanceof OidcUser oidcUser
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
