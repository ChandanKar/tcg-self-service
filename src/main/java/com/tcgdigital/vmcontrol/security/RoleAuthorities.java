package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The application role authorities for a user, derived from the user's flags in the database.
 * One definition for Entra login, password login and the per-request refresh.
 */
public final class RoleAuthorities {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ENV_ADMIN = "ROLE_ENV_ADMIN";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private static final Set<String> APP_ROLES = Set.of(ROLE_USER, ROLE_ENV_ADMIN, ROLE_ADMIN);

    private RoleAuthorities() {
    }

    /** ROLE_USER, plus ROLE_ENV_ADMIN / ROLE_ADMIN from the user's flags. */
    public static Set<GrantedAuthority> forUser(User user) {
        Set<GrantedAuthority> authorities = new HashSet<>();
        authorities.add(new SimpleGrantedAuthority(ROLE_USER));
        if (user.isEnvAdmin()) {
            authorities.add(new SimpleGrantedAuthority(ROLE_ENV_ADMIN));
        }
        if (user.isAdmin()) {
            authorities.add(new SimpleGrantedAuthority(ROLE_ADMIN));
        }
        return authorities;
    }

    /** True for the application roles above (not OIDC_* / SCOPE_* authorities from the token). */
    public static boolean isAppRole(GrantedAuthority authority) {
        return authority != null && APP_ROLES.contains(authority.getAuthority());
    }

    /** The application role names held in a set of authorities. */
    public static Set<String> appRoleNames(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .filter(RoleAuthorities::isAppRole)
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }
}
