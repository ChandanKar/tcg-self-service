package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.model.User;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Custom Authentication token for username/password authentication.
 * Integrates with Spring Security to support dual authentication (OAuth2 + username/password).
 */
public class UsernamePasswordAuthenticationToken extends AbstractAuthenticationToken {

    private final User user;

    /**
     * Create an authenticated token for the given user.
     *
     * @param user the authenticated user
     */
    public UsernamePasswordAuthenticationToken(User user) {
        super(getAuthoritiesForUser(user));
        this.user = user;
        setAuthenticated(true);  // Mark as authenticated
    }

    /**
     * Build authorities/roles for the user based on their admin flags.
     */
    private static Collection<? extends GrantedAuthority> getAuthoritiesForUser(User user) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        // Add basic USER role for all authenticated users
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));

        // Add ADMIN role if user is admin
        if (user.isAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }

        // Add ENV_ADMIN role if user is environment admin
        if (user.isEnvAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ENV_ADMIN"));
        }

        return authorities;
    }

    @Override
    public Object getCredentials() {
        // We don't expose credentials after authentication
        return null;
    }

    @Override
    public Object getPrincipal() {
        return user;
    }

    /**
     * Get the authenticated user.
     *
     * @return the User object
     */
    public User getUser() {
        return user;
    }

    @Override
    public String getName() {
        return user.getEmail();
    }
}

