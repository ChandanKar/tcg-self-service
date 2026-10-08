package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps an authenticated session in step with the database (H10).
 *
 * <p>On every authenticated request: the user is reloaded; an unknown or deactivated user, or a
 * session older than {@code security.session.absolute-timeout}, ends the session (401 JSON for
 * /api/**, redirect to /login?expired for pages); role changes (admin / env admin) replace the
 * authorities in the security context and the session, so a demotion or promotion applies on the
 * very next request without a new login. Token authorities such as OIDC_USER and SCOPE_* are kept.
 */
public class CurrentUserRefreshFilter extends OncePerRequestFilter {

    /** Session attribute: when the session was authenticated (epoch millis). */
    public static final String ISSUED_AT = "auth.issuedAt";

    private static final Logger log = LoggerFactory.getLogger(CurrentUserRefreshFilter.class);

    private final UserRepository userRepository;
    private final Duration absoluteTimeout;
    private final Clock clock;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public CurrentUserRefreshFilter(UserRepository userRepository, Duration absoluteTimeout) {
        this(userRepository, absoluteTimeout, Clock.systemUTC());
    }

    CurrentUserRefreshFilter(UserRepository userRepository, Duration absoluteTimeout, Clock clock) {
        this.userRepository = userRepository;
        this.absoluteTimeout = absoluteTimeout;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }

        Optional<User> user = resolve(authentication);
        if (user.isEmpty()) {
            // A principal type this filter does not manage (e.g. test doubles): leave it alone.
            chain.doFilter(request, response);
            return;
        }

        if (!user.get().isActive()) {
            log.info("Ending session of deactivated user {}", user.get().getUserId());
            reject(request, response);
            return;
        }
        if (sessionExpired(request)) {
            log.info("Ending session of user {}: absolute lifetime {} reached", user.get().getUserId(), absoluteTimeout);
            reject(request, response);
            return;
        }

        refreshAuthorities(authentication, user.get(), request, response);
        chain.doFilter(request, response);
    }

    /**
     * The database user behind the authentication: Entra (OidcUser, by oid) or password / dev
     * (String principal, by id or email). A recognised principal whose row is gone resolves to a
     * placeholder inactive user so the session ends.
     */
    private Optional<User> resolve(Authentication authentication) {
        Object principal = authentication.getPrincipal();
        if (principal instanceof OidcUser oidcUser) {
            String oid = oidcUser.getAttribute("oid");
            if (oid == null) {
                return Optional.empty();
            }
            return Optional.of(userRepository.findByAzureAdObjectId(oid).orElseGet(CurrentUserRefreshFilter::gone));
        }
        if (principal instanceof String id) {
            return Optional.of(userRepository.findById(id)
                    .or(() -> userRepository.findByEmail(id))
                    .orElseGet(CurrentUserRefreshFilter::gone));
        }
        return Optional.empty();
    }

    private static User gone() {
        User placeholder = new User("deleted");
        placeholder.setIsActive(false);
        return placeholder;
    }

    private boolean sessionExpired(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        long now = clock.millis();
        Object issuedAt = session.getAttribute(ISSUED_AT);
        if (!(issuedAt instanceof Long started)) {
            session.setAttribute(ISSUED_AT, now);
            return false;
        }
        return now - started > absoluteTimeout.toMillis();
    }

    private void refreshAuthorities(Authentication authentication, User user,
                                    HttpServletRequest request, HttpServletResponse response) {
        Set<GrantedAuthority> fresh = RoleAuthorities.forUser(user);
        if (RoleAuthorities.appRoleNames(authentication.getAuthorities())
                .equals(RoleAuthorities.appRoleNames(fresh))) {
            return;
        }

        Set<GrantedAuthority> authorities = new HashSet<>(fresh);
        authentication.getAuthorities().stream()
                .filter(a -> !RoleAuthorities.isAppRole(a))
                .forEach(authorities::add);

        Authentication updated;
        if (authentication instanceof OAuth2AuthenticationToken oauth
                && oauth.getPrincipal() instanceof OidcUser oidcUser) {
            DefaultOidcUser principal = new DefaultOidcUser(authorities, oidcUser.getIdToken(), oidcUser.getUserInfo());
            updated = new OAuth2AuthenticationToken(principal, authorities, oauth.getAuthorizedClientRegistrationId());
        } else {
            updated = UsernamePasswordAuthenticationToken.authenticated(authentication.getPrincipal(), null, authorities);
        }

        log.info("Roles of user {} changed to {}; updating the session", user.getUserId(),
                RoleAuthorities.appRoleNames(fresh));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(updated);
        SecurityContextHolder.setContext(context);
        if (request.getSession(false) != null) {
            contextRepository.saveContext(context, request, response);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        if (request.getRequestURI().startsWith("/api/")) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Your session has ended. Sign in again.\"}");
        } else {
            response.sendRedirect("/login?expired");
        }
    }
}
