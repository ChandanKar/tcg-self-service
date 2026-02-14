package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for security and authorization checks.
 * Provides helper methods to check user permissions on resources.
 */
@Service
public class SecurityService {

    private static final Logger log = LoggerFactory.getLogger(SecurityService.class);

    private final UserService userService;
    private final EnvironmentAccessService accessService;

    public SecurityService(UserService userService, EnvironmentAccessService accessService) {
        this.userService = userService;
        this.accessService = accessService;
    }

    /**
     * Check if the current user is an admin.
     */
    public boolean isAdmin() {
        User currentUser = userService.getCurrentUser();
        return currentUser != null && currentUser.isAdmin();
    }

    /**
     * Check if the current user is an environment admin.
     */
    public boolean isEnvAdmin() {
        User currentUser = userService.getCurrentUser();
        return currentUser != null && (currentUser.isAdmin() || currentUser.isEnvAdmin());
    }

    /**
     * Check if the current user has any access to an environment.
     */
    public boolean hasEnvironmentAccess(String environmentId) {
        User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            return false;
        }

        // Admins have access to all environments
        if (currentUser.isAdmin() || currentUser.isEnvAdmin()) {
            return true;
        }

        return accessService.hasAccess(environmentId, currentUser.getUserId());
    }

    /**
     * Check if the current user has at least the required access level on an environment.
     */
    public boolean hasEnvironmentAccessLevel(String environmentId, AccessLevel requiredLevel) {
        User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            return false;
        }

        // Admins have full access to all environments
        if (currentUser.isAdmin()) {
            return true;
        }

        // Env admins have admin-level access to all environments
        if (currentUser.isEnvAdmin() && requiredLevel != AccessLevel.ADMIN) {
            return true;
        }

        return accessService.hasAccessLevel(environmentId, currentUser.getUserId(), requiredLevel);
    }

    /**
     * Check if the current user can manage access for an environment.
     * Requires ADMIN access level on the environment or global admin role.
     */
    public boolean canManageEnvironmentAccess(String environmentId) {
        User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            return false;
        }

        // Global admins can manage any environment
        if (currentUser.isAdmin()) {
            return true;
        }

        // Env admins can manage any environment
        if (currentUser.isEnvAdmin()) {
            return true;
        }

        // Check if user has ADMIN level access on this specific environment
        return accessService.hasAccessLevel(environmentId, currentUser.getUserId(), AccessLevel.ADMIN);
    }

    /**
     * Check if the current user can review access requests.
     */
    public boolean canReviewAccessRequests() {
        User currentUser = userService.getCurrentUser();
        return currentUser != null && (currentUser.isAdmin() || currentUser.isEnvAdmin());
    }

    /**
     * Check if a specific user can perform operations on an environment.
     * Requires at least USER level access.
     */
    public boolean canPerformOperations(String environmentId, String userId) {
        User user = userService.getUserById(userId);

        // Admins can perform operations on any environment
        if (user.isAdmin()) {
            return true;
        }

        return accessService.hasAccessLevel(environmentId, userId, AccessLevel.USER);
    }

    /**
     * Check if the current user can perform operations on an environment.
     */
    public boolean canPerformOperations(String environmentId) {
        User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            return false;
        }

        return canPerformOperations(environmentId, currentUser.getUserId());
    }

    /**
     * Get the current user's access level on an environment.
     * Returns null if user has no access.
     */
    public AccessLevel getAccessLevel(String environmentId) {
        User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            return null;
        }

        // Admins have implicit ADMIN access
        if (currentUser.isAdmin()) {
            return AccessLevel.ADMIN;
        }

        // Env admins have implicit USER access to all environments
        if (currentUser.isEnvAdmin()) {
            return AccessLevel.USER;
        }

        return accessService.getAccess(environmentId, currentUser.getUserId())
                .map(access -> access.getAccessLevel())
                .orElse(null);
    }
}

