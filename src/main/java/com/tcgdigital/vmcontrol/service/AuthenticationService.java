package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Optional;

@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    public static final int MIN_PASSWORD_LENGTH = 12;
    public static final int MAX_PASSWORD_LENGTH = 72; // bcrypt ignores bytes beyond 72

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final boolean passwordLoginEnabled;

    public AuthenticationService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                 AuditService auditService,
                                 @Value("${auth.password-login.enabled:true}") boolean passwordLoginEnabled) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.passwordLoginEnabled = passwordLoginEnabled;
    }

    /** Whether POST /api/auth/login is served at all ({@code auth.password-login.enabled}). */
    public boolean isPasswordLoginEnabled() {
        return passwordLoginEnabled;
    }

    /**
     * Authenticate user by username and password. Only bcrypt hashes are accepted; plain-text
     * values were hashed or cleared by migration V24 and never authenticate.
     */
    @Transactional
    public User authenticateUser(String username, String password) throws UnauthorizedException {
        if (!passwordLoginEnabled) {
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }
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
        boolean authenticated = isBcryptHash(stored) && passwordEncoder.matches(password, stored);

        if (!authenticated) {
            log.warn("Failed login attempt for user: {}", username);
            throw new UnauthorizedException("Invalid credentials or user not found.");
        }

        user.recordLogin();
        User updatedUser = userRepository.save(user);

        log.info("User authenticated successfully via username/password: {} ({})", username, user.getUserId());
        return updatedUser;
    }

    /**
     * Set or update a user's password; always stores a bcrypt hash.
     *
     * @param performedByUserId the admin making the change, recorded in the audit log
     * @throws ValidationException if the password is shorter than {@value #MIN_PASSWORD_LENGTH}
     *         or longer than {@value #MAX_PASSWORD_LENGTH} characters
     */
    @Transactional
    public void setUserPassword(String userId, String password, String performedByUserId) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new ValidationException("Password must be between " + MIN_PASSWORD_LENGTH
                    + " and " + MAX_PASSWORD_LENGTH + " characters");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        user.setPassword(passwordEncoder.encode(password));
        user.setPasswordUpdatedAt(new Timestamp(System.currentTimeMillis()));
        userRepository.save(user);
        log.info("Password set for user {} by {}", userId, performedByUserId);
        auditService.logUserPasswordSet(performedByUserId, userId, user.getEmail());
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
