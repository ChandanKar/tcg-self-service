package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.model.ApiScope;
import com.tcgdigital.vmcontrol.model.ApiToken;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A request authenticated with an API token (E34): the principal is the owner's user id (as for
 * password login), the authorities are the owner's roles plus {@code API_SCOPE_<scope>} for each
 * scope of the token. Never kept in a session.
 */
public class ApiTokenAuthentication extends AbstractAuthenticationToken {

    public static final String SCOPE_PREFIX = "API_SCOPE_";

    private final String userId;
    private final String tokenId;
    private final Set<String> scopes;

    public ApiTokenAuthentication(String userId, ApiToken token, Collection<? extends GrantedAuthority> roleAuthorities) {
        super(withScopes(roleAuthorities, token));
        this.userId = userId;
        this.tokenId = token.getTokenId();
        this.scopes = Set.copyOf(token.scopeList());
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> withScopes(Collection<? extends GrantedAuthority> roles, ApiToken token) {
        Set<GrantedAuthority> all = roles.stream().collect(Collectors.toSet());
        token.scopeList().forEach(s -> all.add(new SimpleGrantedAuthority(SCOPE_PREFIX + s)));
        return all;
    }

    public boolean hasScope(ApiScope scope) {
        return scopes.contains(scope.value());
    }

    public String getTokenId() {
        return tokenId;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }
}
