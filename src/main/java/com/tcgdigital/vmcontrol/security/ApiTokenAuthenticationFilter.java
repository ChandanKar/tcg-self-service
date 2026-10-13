package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.service.ApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Bearer API tokens on /api/** (E34-T02). A request carrying {@code Authorization: Bearer vmc_...}
 * is authenticated by its token alone: an unknown, revoked or expired token, an inactive owner or
 * tokens being off answers 401; a token over its rate answers 429; an endpoint its scopes do not
 * open (or one never open to tokens) answers 403. The authentication lives for this request only:
 * nothing is written to a session, and CSRF does not apply (no cookie is involved). Requests
 * without such a header are left to the session login. Not a bean, so it is not registered twice.
 */
public class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenAuthenticationFilter.class);
    public static final String BEARER = "Bearer " + ApiTokenService.TOKEN_START;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final ApiTokenService tokenService;
    private final ApiScopeRegistry scopes;

    public ApiTokenAuthenticationFilter(ApiTokenService tokenService, ApiScopeRegistry scopes) {
        this.tokenService = tokenService;
        this.scopes = scopes;
    }

    /** Whether the request presents an API token (also used to exempt it from CSRF). */
    public static boolean isTokenRequest(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null && header.startsWith(BEARER);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || !isTokenRequest(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = request.getHeader("Authorization").substring("Bearer ".length()).trim();
        Optional<ApiTokenService.Verified> verified = tokenService.verify(raw);
        if (verified.isEmpty()) {
            write(response, HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid, revoked or expired API token");
            return;
        }
        ApiTokenService.Verified v = verified.get();
        long retryAfter = tokenService.rateLimit(v.token().getTokenId());
        if (retryAfter > 0) {
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            write(response, HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", "API token rate limit reached");
            return;
        }
        ApiTokenAuthentication authentication = new ApiTokenAuthentication(v.user().getUserId(), v.token(),
                RoleAuthorities.forUser(v.user()));
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!scopes.allows(authentication, request.getMethod(), path)) {
            log.debug("API token {} refused {} {}: scope", v.token().getTokenPrefix(), request.getMethod(), path);
            write(response, HttpStatus.FORBIDDEN, "Forbidden", "This API token's scopes do not allow " + request.getMethod() + " " + path);
            return;
        }
        tokenService.touch(v.token().getTokenId(), request.getRemoteAddr());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static void write(HttpServletResponse response, HttpStatus status, String error, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(JSON.writeValueAsString(java.util.Map.of("error", error, "message", message)));
    }
}
