package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Custom OAuth2 User Service that handles user auto-registration and role mapping
 * during Azure AD login.
 */
@Service
public class CustomOAuth2UserService extends OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(CustomOAuth2UserService.class);

    private final UserService userService;

    public CustomOAuth2UserService(UserService userService) {
        this.userService = userService;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        // Load the default OIDC user
        OidcUser oidcUser = super.loadUser(userRequest);

        // Extract Azure AD claims
        String azureAdObjectId = oidcUser.getAttribute("oid");
        String email = extractEmail(oidcUser);
        String displayName = extractDisplayName(oidcUser);

        if (azureAdObjectId == null) {
            log.error("Azure AD object ID (oid) not found in token claims");
            throw new OAuth2AuthenticationException("Missing oid claim in Azure AD token");
        }

        if (email == null) {
            log.error("Email not found in token claims for user: {}", azureAdObjectId);
            throw new OAuth2AuthenticationException("Missing email claim in Azure AD token");
        }

        log.debug("Processing OAuth2 login for: {} ({})", email, azureAdObjectId);

        // Find or create user in our database
        User user = userService.findOrCreateUser(azureAdObjectId, email, displayName);

        // Check if user is active
        if (!user.isActive()) {
            log.warn("Inactive user attempted login: {}", email);
            throw new OAuth2AuthenticationException("User account is deactivated");
        }

        // Build authorities based on user roles
        Set<GrantedAuthority> authorities = buildAuthorities(user, oidcUser);

        log.info("User authenticated: {} with roles: {}", email, authorities);

        // Return enhanced OIDC user with our custom authorities
        return new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
    }

    /**
     * Extract email from OIDC user.
     * Azure AD may provide email in different claims depending on configuration.
     */
    private String extractEmail(OidcUser oidcUser) {
        // Try standard email claim first
        String email = oidcUser.getEmail();

        if (email == null) {
            // Try preferred_username (common in Azure AD)
            email = oidcUser.getAttribute("preferred_username");
        }

        if (email == null) {
            // Try upn (User Principal Name)
            email = oidcUser.getAttribute("upn");
        }

        return email;
    }

    /**
     * Extract display name from OIDC user.
     */
    private String extractDisplayName(OidcUser oidcUser) {
        String displayName = oidcUser.getFullName();

        if (displayName == null) {
            displayName = oidcUser.getAttribute("name");
        }

        if (displayName == null) {
            // Fallback to combining given_name and family_name
            String givenName = oidcUser.getAttribute("given_name");
            String familyName = oidcUser.getAttribute("family_name");
            if (givenName != null && familyName != null) {
                displayName = givenName + " " + familyName;
            } else if (givenName != null) {
                displayName = givenName;
            }
        }

        if (displayName == null) {
            // Last resort - use email prefix
            String email = extractEmail(oidcUser);
            if (email != null && email.contains("@")) {
                displayName = email.substring(0, email.indexOf("@"));
            } else {
                displayName = "Unknown User";
            }
        }

        return displayName;
    }

    /**
     * Build Spring Security authorities based on user roles.
     */
    private Set<GrantedAuthority> buildAuthorities(User user, OidcUser oidcUser) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        // Add default authorities from OIDC token
        authorities.addAll(oidcUser.getAuthorities());

        // Add our application roles
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));

        if (user.isEnvAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ENV_ADMIN"));
        }

        if (user.isAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }

        return authorities;
    }

    /**
     * Process an OIDC user directly (for testing purposes).
     * This method simulates the user registration part of the OAuth2 flow.
     *
     * @param oidcUser the OIDC user to process
     * @return the registered or updated User entity
     */
    public User processOidcUser(OidcUser oidcUser) {
        String azureAdObjectId = oidcUser.getAttribute("oid");
        String email = extractEmail(oidcUser);
        String displayName = extractDisplayName(oidcUser);

        // Use fallback for missing oid
        if (azureAdObjectId == null) {
            azureAdObjectId = oidcUser.getSubject();
        }

        // Use fallback for missing email
        if (email == null) {
            email = azureAdObjectId + "@unknown.local";
        }

        // Use fallback for missing display name
        if (displayName == null) {
            displayName = "Unknown User";
        }

        log.debug("Processing OIDC user: {} ({})", email, azureAdObjectId);

        return userService.findOrCreateUser(azureAdObjectId, email, displayName);
    }
}

