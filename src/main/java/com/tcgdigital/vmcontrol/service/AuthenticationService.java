package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;

@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthenticationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Authenticate user by username and password.
     * Supports lazy migration: if a stored password is plain-text (legacy), it is
     * transparently re-hashed with bcrypt on the first successful login.
     */
    @Transactional
    public User authenticateUser(String username, String password) throws UnauthorizedException {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("Login attempt with empty username or password");
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }

        Optional<User> optionalUser = userRepository.findByUsername(username);

        if (optionalUser.isEmpty()) {
            log.warn("Login attempt for non-existent username: {}", username);
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }

        User user = optionalUser.get();

        if (!user.isActive()) {
            log.warn("Login attempt for inactive user: {} ({})", username, user.getUserId());
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }

        if (user.getPassword() == null || user.getPassword().isBlank()) {
            log.warn("Login attempt for user without password configured: {}", username);
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }

        String stored = user.getPassword();
        boolean authenticated = false;

        if (isBcryptHash(stored)) {
            // Modern path — bcrypt compare
            authenticated = passwordEncoder.matches(password, stored);
        } else {
            // Legacy plain-text path — migrate on success
            if (stored.equals(password)) {
                authenticated = true;
                String hashed = passwordEncoder.encode(password);
                user.setPassword(hashed);
                user.setPasswordUpdatedAt(new Timestamp(System.currentTimeMillis()));
                log.info("Migrated plain-text password to bcrypt for user: {}", username);
            }
        }

        if (!authenticated) {
            log.warn("Failed login attempt for user: {}", username);
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }

        user.setLastLoginAt(new Timestamp(System.currentTimeMillis()));
        User updatedUser = userRepository.save(user);

        log.info("User authenticated successfully via username/password: {} ({})", username, user.getUserId());
        return updatedUser;
    }

    /**
     * Set or update a user's password — always stores a bcrypt hash.
     */
    @Transactional
    public void setUserPassword(String userId, String password) {
        Optional<User> optionalUser = userRepository.findById(userId);
        if (optionalUser.isPresent()) {
            User user = optionalUser.get();
            user.setPassword(passwordEncoder.encode(password));
            user.setPasswordUpdatedAt(new Timestamp(System.currentTimeMillis()));
            userRepository.save(user);
            log.info("Password updated for user: {}", userId);
        }
    }

    public boolean userHasPassword(String userId) {
        Optional<User> optionalUser = userRepository.findById(userId);
        return optionalUser.isPresent()
                && optionalUser.get().getPassword() != null
                && !optionalUser.get().getPassword().isBlank();
    }

    public boolean isUsernameAvailable(String username) {
        return !userRepository.existsByUsername(username);
    }

    private boolean isBcryptHash(String value) {
        return value != null && value.startsWith("$2");
    }
}
