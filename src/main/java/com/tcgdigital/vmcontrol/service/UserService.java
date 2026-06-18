package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.security.UsernamePasswordAuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Service for User management operations.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final AuditService auditService;

    @Value("${app.initial-admin-email:}")
    private String initialAdminEmail;

    public UserService(UserRepository userRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    /**
     * Find or create a user during OAuth2 login.
     * If user exists, update display name and last login.
     * If user doesn't exist, create a new user.
     */
    @Transactional
    public User findOrCreateUser(String azureAdObjectId, String email, String displayName) {
        Optional<User> existingUser = userRepository.findByAzureAdObjectId(azureAdObjectId);

        if (existingUser.isPresent()) {
            User user = existingUser.get();

            // Update display name if changed
            if (!user.getDisplayName().equals(displayName)) {
                user.setDisplayName(displayName);
                log.info("Updated display name for user: {} -> {}", user.getEmail(), displayName);
            }

            // Check if this user should be promoted to admin based on initial-admin-email
            if (initialAdminEmail != null && !initialAdminEmail.isEmpty()
                && email.equalsIgnoreCase(initialAdminEmail) && !user.isAdmin()) {
                user.setAdmin(true);
                log.info("Auto-promoting existing user to admin based on initial-admin-email config: {}", email);
            }

            // Update last login
            user.setLastLoginAt(new Timestamp(System.currentTimeMillis()));

            log.debug("User logged in: {} ({})", user.getEmail(), user.getUserId());
            return userRepository.save(user);
        }

        // Fallback: look up by email to handle legacy-migrated users whose azure_ad_object_id is null
        Optional<User> userByEmail = userRepository.findByEmail(email);
        if (userByEmail.isPresent()) {
            User user = userByEmail.get();
            // Link the Azure AD object ID now that the user has logged in via Entra ID
            user.setAzureAdObjectId(azureAdObjectId);
            log.info("Linked Azure AD object ID to existing user: {} ({})", user.getEmail(), user.getUserId());
            if (!user.getDisplayName().equals(displayName)) {
                user.setDisplayName(displayName);
            }
            if (initialAdminEmail != null && !initialAdminEmail.isEmpty()
                && email.equalsIgnoreCase(initialAdminEmail) && !user.isAdmin()) {
                user.setAdmin(true);
                log.info("Auto-promoting legacy user to admin based on initial-admin-email config: {}", email);
            }
            user.setLastLoginAt(new Timestamp(System.currentTimeMillis()));
            return userRepository.save(user);
        }

        // Create new user
        User newUser = User.fromAzureAd(azureAdObjectId, email, displayName);
        newUser.setLastLoginAt(new Timestamp(System.currentTimeMillis()));

        // Check if this is the initial admin
        if (initialAdminEmail != null && !initialAdminEmail.isEmpty()
            && email.equalsIgnoreCase(initialAdminEmail)) {
            newUser.setAdmin(true);
            log.info("Auto-promoting user to admin based on initial-admin-email config: {}", email);
        }

        User saved = userRepository.save(newUser);
        log.info("Created new user: {} ({})", saved.getEmail(), saved.getUserId());

        // Audit logging
        auditService.logUserCreated(saved.getUserId(), saved.getEmail());

        return saved;
    }

    /**
     * Update last login timestamp for a user.
     */
    @Transactional
    public void updateLastLogin(String userId) {
        userRepository.updateLastLoginAt(userId, new Timestamp(System.currentTimeMillis()));
    }

    /**
     * Get all active users.
     */
    public List<User> getAllActiveUsers() {
        return userRepository.findByIsActiveTrueOrderByDisplayNameAsc();
    }

    /**
     * Get all users (including inactive).
     */
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    /**
     * Get user by ID.
     */
    public User getUserById(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
    }

    /**
     * Get user by email.
     */
    public Optional<User> getUserByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    /**
     * Get user by Azure AD object ID.
     */
    public Optional<User> getUserByAzureAdObjectId(String azureAdObjectId) {
        return userRepository.findByAzureAdObjectId(azureAdObjectId);
    }

    /**
     * Search users by email or display name.
     */
    public List<User> searchUsers(String searchTerm) {
        return userRepository.searchActiveUsers(searchTerm);
    }

    /**
     * Toggle admin role for a user.
     */
    @Transactional
    public User toggleAdmin(String userId, String performedByUserId) {
        User user = getUserById(userId);

        // Prevent removing the last admin
        if (user.isAdmin() && userRepository.countByAdminTrueAndIsActiveTrue() <= 1) {
            throw new ValidationException("Cannot remove the last admin user");
        }

        boolean newAdminStatus = !user.isAdmin();
        user.setAdmin(newAdminStatus);

        User saved = userRepository.save(user);
        log.info("User {} admin status changed to {} by {}", userId, newAdminStatus, performedByUserId);

        // Audit logging
        auditService.logUserRoleChanged(performedByUserId, userId, "admin", newAdminStatus);

        return saved;
    }

    /**
     * Toggle environment admin role for a user.
     */
    @Transactional
    public User toggleEnvAdmin(String userId, String performedByUserId) {
        User user = getUserById(userId);

        boolean newEnvAdminStatus = !user.isEnvAdmin();
        user.setEnvAdmin(newEnvAdminStatus);

        User saved = userRepository.save(user);
        log.info("User {} env_admin status changed to {} by {}", userId, newEnvAdminStatus, performedByUserId);

        // Audit logging
        auditService.logUserRoleChanged(performedByUserId, userId, "env_admin", newEnvAdminStatus);

        return saved;
    }

    /**
     * Set admin role explicitly.
     */
    @Transactional
    public User setAdmin(String userId, boolean isAdmin, String performedByUserId) {
        User user = getUserById(userId);

        // Prevent removing the last admin
        if (user.isAdmin() && !isAdmin && userRepository.countByAdminTrueAndIsActiveTrue() <= 1) {
            throw new ValidationException("Cannot remove the last admin user");
        }

        user.setAdmin(isAdmin);

        User saved = userRepository.save(user);
        log.info("User {} admin status set to {} by {}", userId, isAdmin, performedByUserId);

        auditService.logUserRoleChanged(performedByUserId, userId, "admin", isAdmin);

        return saved;
    }

    /**
     * Set environment admin role explicitly.
     */
    @Transactional
    public User setEnvAdmin(String userId, boolean isEnvAdmin, String performedByUserId) {
        User user = getUserById(userId);

        user.setEnvAdmin(isEnvAdmin);

        User saved = userRepository.save(user);
        log.info("User {} env_admin status set to {} by {}", userId, isEnvAdmin, performedByUserId);

        auditService.logUserRoleChanged(performedByUserId, userId, "env_admin", isEnvAdmin);

        return saved;
    }

    /**
     * Deactivate a user (soft delete).
     */
    @Transactional
    public User deactivateUser(String userId, String performedByUserId) {
        User user = getUserById(userId);

        // Prevent deactivating the last admin
        if (user.isAdmin() && userRepository.countByAdminTrueAndIsActiveTrue() <= 1) {
            throw new ValidationException("Cannot deactivate the last admin user");
        }

        // Prevent self-deactivation
        if (userId.equals(performedByUserId)) {
            throw new ValidationException("Cannot deactivate your own account");
        }

        user.setIsActive(false);

        User saved = userRepository.save(user);
        log.info("User {} deactivated by {}", userId, performedByUserId);

        auditService.logUserDeactivated(performedByUserId, userId);

        return saved;
    }

    /**
     * Reactivate a user.
     */
    @Transactional
    public User reactivateUser(String userId, String performedByUserId) {
        User user = getUserById(userId);

        user.setIsActive(true);

        User saved = userRepository.save(user);
        log.info("User {} reactivated by {}", userId, performedByUserId);

        auditService.logUserReactivated(performedByUserId, userId);

        return saved;
    }

    /**
     * Get the currently authenticated user.
     * Returns null if not authenticated or user not found.
     */
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }

        Object principal = authentication.getPrincipal();

        // Check for username/password authentication token
        if (authentication instanceof UsernamePasswordAuthenticationToken) {
            return ((UsernamePasswordAuthenticationToken) authentication).getUser();
        }

        // Check for OAuth2/OIDC authentication
        if (principal instanceof OidcUser oidcUser) {
            String azureAdObjectId = oidcUser.getAttribute("oid");
            if (azureAdObjectId != null) {
                return userRepository.findByAzureAdObjectId(azureAdObjectId).orElse(null);
            }
        }

        // For @WithMockUser in tests (UserDetails principal from Spring Security test support)
        if (principal instanceof UserDetails ud) {
            String login = ud.getUsername();
            return userRepository.findByEmail(login)
                    .or(() -> userRepository.findById(login))
                    .orElse(null);
        }

        // For dev mode X-User-Id header or manual SecurityContext setup in tests
        if (principal instanceof String s) {
            return userRepository.findByEmail(s)
                    .or(() -> userRepository.findById(s))
                    .orElse(null);
        }

        return null;
    }

    /**
     * Get the current user ID.
     * Throws UnauthorizedException if not authenticated or user not found in database.
     * This ensures the returned ID always references a valid APP_USER row (FK-safe).
     */
    public String getCurrentUserId() {
        User currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new UnauthorizedException(
                    "No authenticated user found. Please log in and try again.");
        }
        return currentUser.getUserId();
    }

    /**
     * Check if the current user is an admin.
     */
    public boolean isCurrentUserAdmin() {
        User currentUser = getCurrentUser();
        return currentUser != null && currentUser.isAdmin();
    }

    /**
     * Check if the current user is an environment admin.
     */
    public boolean isCurrentUserEnvAdmin() {
        User currentUser = getCurrentUser();
        return currentUser != null && (currentUser.isAdmin() || currentUser.isEnvAdmin());
    }

    /**
     * Get admin users.
     */
    public List<User> getAdminUsers() {
        return userRepository.findByAdminTrueAndIsActiveTrue();
    }

    /**
     * Get environment admin users.
     */
    public List<User> getEnvAdminUsers() {
        return userRepository.findByEnvAdminTrueAndIsActiveTrue();
    }
}

