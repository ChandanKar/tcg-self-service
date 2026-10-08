package com.tcgdigital.vmcontrol.support;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test base that runs the real Entra ID security chain, so {@code @PreAuthorize}
 * rules are evaluated. Each test gets four users — admin, envAdmin, operator and viewer — and
 * request helpers that sign in as them. Operator and viewer have no access until a test grants it.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "entraid.enabled=true",
        "spring.security.oauth2.client.registration.azure.provider=azure",
        "spring.security.oauth2.client.registration.azure.client-id=test",
        "spring.security.oauth2.client.registration.azure.client-secret=test",
        "spring.security.oauth2.client.registration.azure.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.azure.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.registration.azure.scope=openid,profile,email",
        "spring.security.oauth2.client.provider.azure.authorization-uri=http://localhost/authorize",
        "spring.security.oauth2.client.provider.azure.token-uri=http://localhost/token",
        "spring.security.oauth2.client.provider.azure.jwk-set-uri=http://localhost/keys",
        "spring.security.oauth2.client.provider.azure.user-info-uri=http://localhost/userinfo",
        "spring.security.oauth2.client.provider.azure.user-name-attribute=name",
        "entraid.end-session-uri=https://login.microsoftonline.com/test-tenant/oauth2/v2.0/logout"
})
public abstract class SecuredWebTestBase extends AbstractIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    protected User admin;
    protected User envAdmin;
    protected User operator;
    protected User viewer;

    @BeforeEach
    void createRoleUsers() {
        admin = newUser("admin@secured.test", true, false);
        envAdmin = newUser("envadmin@secured.test", false, true);
        operator = newUser("operator@secured.test", false, false);
        viewer = newUser("viewer@secured.test", false, false);
    }

    protected RequestPostProcessor asAdmin() {
        return as(admin);
    }

    protected RequestPostProcessor asEnvAdmin() {
        return as(envAdmin);
    }

    protected RequestPostProcessor asUser() {
        return as(operator);
    }

    protected RequestPostProcessor asViewer() {
        return as(viewer);
    }

    /**
     * Sign in as {@code user} (same authorities CustomOAuth2UserService grants at login) and send a
     * valid CSRF token, as the browser app does for mutating requests (E03-T10).
     */
    protected RequestPostProcessor as(User user) {
        RequestPostProcessor signedIn = asWithoutCsrf(user);
        return request -> csrf().postProcessRequest(signedIn.postProcessRequest(request));
    }

    /** Signed in as {@code user} but without a CSRF token (for CSRF tests). */
    protected RequestPostProcessor asWithoutCsrf(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (user.isEnvAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ENV_ADMIN"));
        }
        if (user.isAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return oidcLogin()
                .idToken(token -> token
                        .claim("oid", user.getAzureAdObjectId())
                        .claim("email", user.getEmail())
                        .claim("name", user.getDisplayName()))
                .authorities(authorities);
    }

    protected EnvironmentAccess grantEnv(User user, String environmentId, AccessLevel level) {
        return grant(user, AccessScopeType.ENVIRONMENT, environmentId, level);
    }

    protected EnvironmentAccess grantGroup(User user, String groupId, AccessLevel level) {
        return grant(user, AccessScopeType.GROUP, groupId, level);
    }

    /** Assert the status and the shared error body ({@code error}, {@code message}). */
    protected ResultActions expectError(ResultActions result, int httpStatus) throws Exception {
        return result.andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists());
    }
}
