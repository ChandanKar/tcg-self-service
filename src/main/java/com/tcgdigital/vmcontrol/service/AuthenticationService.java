package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * Service for username/password based authentication.
 * Supports plain text password comparison for alpha version.
 * Will migrate to password hashing (bcrypt/argon2) in v1.0
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository userRepository;

    public AuthenticationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Authenticate user by username and plain text password.
     * Updates last_login_at on successful authentication.
     *
     * @param username user's username
     * @param password user's plain text password
     * @return authenticated User object
     * @throws UnauthorizedException if authentication fails
     */
    @Transactional
    public User authenticateUser(String username, String password) throws UnauthorizedException {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("Login attempt with empty username or password");
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }

        // Look up user by username
        Optional<User> optionalUser = userRepository.findByUsername(username);

        if (optionalUser.isEmpty()) {
            log.warn("Login attempt for non-existent username: {}", username);
            // Use generic error message to prevent username enumeration
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }

        User user = optionalUser.get();

        // Check if user is active
        if (!user.isActive()) {
            log.warn("Login attempt for inactive user: {} ({})", username, user.getUserId());
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }

        // Check if user has a password set
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            log.warn("Login attempt for user without password configured: {}", username);
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }

        // Compare plain text password (alpha version)
        // TODO: v1.0 - Replace with bcrypt/argon2 hashing
        if (!user.getPassword().equals(password)) {
            log.warn("Failed login attempt for user: {}", username);
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }

        // Authentication successful - update last login
        user.setLastLoginAt(new Timestamp(System.currentTimeMillis()));
        User updatedUser = userRepository.save(user);

        log.info("User authenticated successfully via username/password: {} ({})", username, user.getUserId());

        return updatedUser;
    }

    /**
     * Verify if a user has a password configured.
     * Used for determining available login methods.
     *
     * @param userId user's ID
     * @return true if user has a password, false otherwise
     */
    public boolean userHasPassword(String userId) {
        Optional<User> optionalUser = userRepository.findById(userId);
        return optionalUser.isPresent() && optionalUser.get().getPassword() != null
                && !optionalUser.get().getPassword().isBlank();
    }

    /**
     * Check if a username is available (not already taken).
     *
     * @param username username to check
     * @return true if username is available, false if taken
     */
    public boolean isUsernameAvailable(String username) {
        return !userRepository.existsByUsername(username);
    }

    /**
     * Set or update a user's password (plain text for alpha).
     * Updates password_updated_at timestamp.
     *
     * @param userId user's ID
     * @param password new plain text password
     */
    @Transactional
    public void setUserPassword(String userId, String password) {
        Optional<User> optionalUser = userRepository.findById(userId);

        if (optionalUser.isPresent()) {
            User user = optionalUser.get();
            user.setPassword(password);
            user.setPasswordUpdatedAt(new Timestamp(System.currentTimeMillis()));
            userRepository.save(user);
            log.info("Password updated for user: {}", userId);
        }
    }
}

