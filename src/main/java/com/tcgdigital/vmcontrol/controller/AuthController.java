package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.LoginRequest;
import com.tcgdigital.vmcontrol.dto.LoginResponse;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.AuthenticationService;
import com.tcgdigital.vmcontrol.service.LoginThrottleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for authentication endpoints.
 * Handles username/password login via POST /api/auth/login.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String INVALID_CREDENTIALS = "Invalid credentials or user not found.";

    private final AuthenticationService authenticationService;
    private final LoginThrottleService loginThrottleService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationService authenticationService, LoginThrottleService loginThrottleService) {
        this.authenticationService = authenticationService;
        this.loginThrottleService = loginThrottleService;
    }

    /**
     * Sign-in options for the login page; anonymous. Entra ID sign-in is always offered.
     */
    @GetMapping("/options")
    public Map<String, Boolean> options() {
        return Map.of("passwordLoginEnabled", authenticationService.isPasswordLoginEnabled());
    }

    /**
     * Login endpoint for username/password authentication.
     *
     * <p>Failed attempts are throttled per username and per client IP (429 with Retry-After).
     * On success the session id is rotated and the session holds only the user id and roles,
     * never the User entity or its password hash.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestBody LoginRequest loginRequest,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!authenticationService.isPasswordLoginEnabled()) {
            return ResponseEntity.notFound().build();
        }

        String username = loginRequest.getUsername();
        String ip = request.getRemoteAddr();

        Optional<Duration> blocked = loginThrottleService.blockedFor(username, ip);
        if (blocked.isPresent()) {
            log.warn("Login throttled for username '{}' from {}", username, ip);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, blocked.get().toSeconds())))
                    .body(new LoginResponse(false, "Too many failed attempts. Try again later."));
        }

        try {
            User user = authenticationService.authenticateUser(username, loginRequest.getPassword());
            loginThrottleService.recordSuccess(username);

            // Rotate the session id so a pre-login session id cannot be reused (session fixation).
            if (request.getSession(false) != null) {
                request.changeSessionId();
            } else {
                request.getSession(true);
            }

            SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
            securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    user.getUserId(), null, authoritiesFor(user)));
            SecurityContextHolder.setContext(securityContext);
            securityContextRepository.saveContext(securityContext, request, response);

            log.info("User {} logged in successfully via username/password", user.getUsername());
            return ResponseEntity.ok(new LoginResponse(true, "Login successful", user));

        } catch (UnauthorizedException e) {
            loginThrottleService.recordFailure(username, ip);
            log.warn("Failed password login for username '{}' from {}", username, ip);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(false, INVALID_CREDENTIALS));

        } catch (Exception e) {
            log.error("Unexpected error during login", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new LoginResponse(false, "Login failed. Please try again later."));
        }
    }

    private static List<GrantedAuthority> authoritiesFor(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (user.isAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        if (user.isEnvAdmin()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ENV_ADMIN"));
        }
        return authorities;
    }
}
