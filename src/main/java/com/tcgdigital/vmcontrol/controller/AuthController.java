package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.LoginRequest;
import com.tcgdigital.vmcontrol.dto.LoginResponse;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.AuthenticationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpSession;

/**
 * REST controller for authentication endpoints.
 * Handles username/password login via POST /api/auth/login.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * Login endpoint for username/password authentication.
     * Creates a Spring Security session on successful authentication.
     *
     * @param loginRequest contains username and password
     * @param session HTTP session to store authentication
     * @return LoginResponse with success status and user details
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest loginRequest, HttpSession session) {
        try {
            // Validate request
            if (loginRequest.getUsername() == null || loginRequest.getUsername().isBlank()) {
                log.warn("Login attempt with empty username");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(false, "Invalid credentials or user not found."));
            }

            if (loginRequest.getPassword() == null || loginRequest.getPassword().isBlank()) {
                log.warn("Login attempt with empty password");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new LoginResponse(false, "Invalid credentials or user not found."));
            }

            // Authenticate user
            User user = authenticationService.authenticateUser(loginRequest.getUsername(), loginRequest.getPassword());

            // Store user info in session for authentication
            session.setAttribute("userId", user.getUserId());
            session.setAttribute("username", user.getUsername());
            session.setAttribute("userEmail", user.getEmail());

            log.info("User {} logged in successfully via username/password", user.getUsername());

            // Return success response with user details
            return ResponseEntity.ok(new LoginResponse(true, "Login successful", user));

        } catch (UnauthorizedException e) {
            // Generic error message - don't reveal whether username or password was wrong
            log.warn("Authentication failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new LoginResponse(false, "Invalid credentials or user not found."));

        } catch (Exception e) {
            log.error("Unexpected error during login", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new LoginResponse(false, "Login failed. Please try again later."));
        }
    }
}



