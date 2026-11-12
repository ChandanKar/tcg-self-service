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

    /** Live settings (U14-T06): password sign-in can be switched off without a restart. Absent in unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    public AuthenticationService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                                 AuditService auditService,
                                 @Value("${auth.password-login.enabled:true}") boolean passwordLoginEnabled) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.passwordLoginEnabled = passwordLoginEnabled;
    }

    /** Whether POST /api/auth/login is served at all ({@code auth.password-login.enabled}, live from Settings). */
    public boolean isPasswordLoginEnabled() {
        return settings == null ? passwordLoginEnabled : settings.getBoolean("auth.password-login.enabled", passwordLoginEnabled);
    }

    /**
     * Authenticate user by username and password. Only bcrypt hashes are accepted; plain-text
     * values were hashed or cleared by migration V24 and never authenticate.
     */
    @Transactional
    public User authenticateUser(String username, String password) throws UnauthorizedException {
        if (!isPasswordLoginEnabled()) {
            throw new UnauthorizedException("Invalid username or password.");
        }
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            log.warn("Login attempt with empty username or password");
            throw new UnauthorizedException("Invalid username or password.");
        }

        Optional<User> optionalUser = userRepository.findByUsername(username);
        if (optionalUser.isEmpty() && username.contains("@")) {
            // U20-T07: people added with a password sign in with their email.
            optionalUser = userRepository.findByEmail(username.trim().toLowerCase(java.util.Locale.ROOT))
                    .filter(u -> u.getUsername() != null);
        }

        if (optionalUser.isEmpty()) {
            log.warn("Login attempt for non-existent username: {}", username);
            throw new UnauthorizedException("Invalid username or password.");
        }

        User user = optionalUser.get();

        if (user.isServiceAccount()) {
            log.warn("Password login attempt for service account {}", user.getUserId());
            throw new UnauthorizedException("Invalid username or password.");
        }

        if (!user.isActive()) {
            log.warn("Login attempt for inactive user: {} ({})", username, user.getUserId());
            throw new UnauthorizedException("Invalid username or password.");
        }

        if (user.getPassword() == null || user.getPassword().isBlank()) {
            log.warn("Login attempt for user without password configured: {}", username);
            throw new UnauthorizedException("Invalid username or password.");
        }

        String stored = user.getPassword();
        boolean authenticated = isBcryptHash(stored) && passwordEncoder.matches(password, stored);

        if (!authenticated) {
            log.warn("Failed login attempt for user: {}", username);
            throw new UnauthorizedException("Invalid username or password.");
        }

        if (user.isPasswordChangeRequired()) {
            // Not signed in yet: the caller asks for a new password first (U20-T07).
            log.info("Initial password accepted for {}; a new one is required", user.getUserId());
            return user;
        }
        user.recordLogin();
        User updatedUser = userRepository.save(user);

        log.info("User authenticated successfully via username/password: {} ({})", username, user.getUserId());
        return updatedUser;
    }

    /**
     * First sign-in with an initial password (U20-T07): the current password is checked as for a
     * sign-in, the new one must be 12 to 72 characters and differ from it; it replaces the initial
     * one, the change is no longer required, and the sign-in is recorded.
     */
    @Transactional
    public User changeInitialPassword(String username, String currentPassword, String newPassword) {
        User user = authenticateUser(username, currentPassword);
        if (!user.isPasswordChangeRequired()) {
            throw new ValidationException("Your password is already your own. Sign in with it.");
        }
        validateLength(newPassword);
        if (newPassword.equals(currentPassword)) {
            throw new ValidationException("Choose a password other than the one you were given.");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordUpdatedAt(new Timestamp(System.currentTimeMillis()));
        user.setPasswordChangeRequired(false);
        user.recordLogin();
        User saved = userRepository.save(user);
        auditService.logUserPasswordSet(saved.getUserId(), saved.getUserId(), saved.getEmail());
        log.info("{} chose their own password at first sign-in", saved.getUserId());
        return saved;
    }

    /**
     * An initial password for a person just added (U20-T07): their email becomes their username, the
     * password is stored as a bcrypt hash and must be changed at first sign-in.
     */
    @Transactional
    public void setInitialPassword(User user, String password, String performedByUserId) {
        validateLength(password);
        String username = user.getEmail().toLowerCase(java.util.Locale.ROOT);
        userRepository.findByUsername(username).filter(other -> !other.getUserId().equals(user.getUserId())).ifPresent(other -> {
            throw new ValidationException(user.getEmail() + " is already someone's username");
        });
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setPasswordUpdatedAt(new Timestamp(System.currentTimeMillis()));
        user.setPasswordChangeRequired(true);
        userRepository.save(user);
        auditService.logUserPasswordSet(performedByUserId, user.getUserId(), user.getEmail());
    }

    /** 12 to 72 characters (bcrypt reads at most 72 bytes). */
    public static void validateLength(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new ValidationException("Password must be between " + MIN_PASSWORD_LENGTH
                    + " and " + MAX_PASSWORD_LENGTH + " characters");
        }
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
        validateLength(password);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            // U21-T21: a person added from the directory or by email has no username, and password
            // sign-in finds people by username (or by email only when they have one), so the password
            // was saved but never worked. Their email becomes their username, as for an initial
            // password (U20-T07), and they choose their own at first sign-in.
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                throw new ValidationException("This person has no email address to sign in with");
            }
            String username = user.getEmail().trim().toLowerCase(java.util.Locale.ROOT);
            userRepository.findByUsername(username).filter(other -> !other.getUserId().equals(user.getUserId())).ifPresent(other -> {
                throw new ValidationException(user.getEmail() + " is already someone's username");
            });
            user.setUsername(username);
            user.setPasswordChangeRequired(true);
        }
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
