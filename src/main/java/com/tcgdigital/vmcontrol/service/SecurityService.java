package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for security and authorization checks.
 * Provides helper methods to check user permissions on resources.
 */
@Service
public class SecurityService {

    private static final Logger log = LoggerFactory.getLogger(SecurityService.class);

    private final UserService userService;
    private final EnvironmentAccessService accessService;
    private final VmGroupRepository vmGroupRepository;

    public SecurityService(UserService userService, EnvironmentAccessService accessService,
                           VmGroupRepository vmGroupRepository) {
        this.userService = userService;
        this.accessService = accessService;
        this.vmGroupRepository = vmGroupRepository;
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

    // ============= Group-scoped access (env or group grant) =============

    /** The current user's effective environment level (see the {@link User} overload). */
    public AccessLevel effectiveEnvLevel(String environmentId) {
        return effectiveEnvLevel(userService.getCurrentUser(), environmentId);
    }

    /**
     * A user's effective level on an environment: the greater of their global role and any
     * active ENVIRONMENT-scoped grant. Null when they have neither.
     */
    public AccessLevel effectiveEnvLevel(User user, String environmentId) {
        if (user == null) {
            return null;
        }
        AccessLevel level = globalRoleLevel(user);
        if (level == AccessLevel.ADMIN) {
            return level;
        }
        return higher(level, accessService
                .getActiveGrant(user.getUserId(), AccessScopeType.ENVIRONMENT, environmentId)
                .map(EnvironmentAccess::getAccessLevel)
                .orElse(null));
    }

    /** The current user's effective level on one group (see the {@link User} overload). */
    public AccessLevel effectiveGroupLevel(String groupId) {
        return effectiveGroupLevel(userService.getCurrentUser(), groupId);
    }

    /**
     * A user's effective level on one group: the greatest of their global role, an ENVIRONMENT
     * grant on the group's environment, and a GROUP grant on the group itself. Null when they
     * have none of those (and null for an unknown group unless a role covers it).
     */
    public AccessLevel effectiveGroupLevel(User user, String groupId) {
        if (user == null) {
            return null;
        }
        AccessLevel level = globalRoleLevel(user);
        if (level == AccessLevel.ADMIN) {
            return level;
        }
        VmGroup group = vmGroupRepository.findById(groupId).orElse(null);
        if (group == null) {
            return level;
        }
        level = higher(level, accessService.getActiveGrant(user.getUserId(),
                        AccessScopeType.ENVIRONMENT, group.getEnvironment().getEnvironmentId())
                .map(EnvironmentAccess::getAccessLevel).orElse(null));
        level = higher(level, accessService.getActiveGrant(user.getUserId(),
                        AccessScopeType.GROUP, groupId)
                .map(EnvironmentAccess::getAccessLevel).orElse(null));
        return level;
    }

    /**
     * Whether the current user has any access (env or group) to a group.
     */
    public boolean hasGroupAccess(String groupId) {
        return effectiveGroupLevel(groupId) != null;
    }

    /**
     * Whether the current user has at least {@code requiredLevel} on a group.
     */
    public boolean hasGroupAccessLevel(String groupId, AccessLevel requiredLevel) {
        return atLeast(effectiveGroupLevel(groupId), requiredLevel);
    }

    /**
     * Whether {@code userId} has at least {@code requiredLevel} on a group. Explicit-user
     * variant for callers that act on behalf of someone other than the security principal
     * (VM operations, automation rules).
     */
    public boolean hasGroupAccessLevelForUser(String userId, String groupId, AccessLevel requiredLevel) {
        return atLeast(effectiveGroupLevel(loadUser(userId), groupId), requiredLevel);
    }

    /**
     * Whether the current user can start/stop anything in an environment: environment-level
     * USER (or a global role), or USER on at least one group in it.
     */
    public boolean canOperateInEnvironment(String environmentId) {
        User user = userService.getCurrentUser();
        if (user == null) {
            return false;
        }
        if (atLeast(effectiveEnvLevel(user, environmentId), AccessLevel.USER)) {
            return true;
        }
        List<String> groupIds = vmGroupRepository.findByEnvironmentId(environmentId).stream()
                .map(VmGroup::getGroupId).toList();
        return accessService.getActiveGroupGrantLevels(user.getUserId(), groupIds).values().stream()
                .anyMatch(l -> l.ordinal() >= AccessLevel.USER.ordinal());
    }

    /**
     * Whether the current user can see any part of an environment — any environment access, a
     * global role, or a grant on at least one group in it.
     */
    public boolean canViewEnvironment(String environmentId) {
        return !getVisibleGroupIds(environmentId).isEmpty()
                || effectiveEnvLevel(environmentId) != null;
    }

    /** The current user's visible group ids in an environment (see the {@link User} overload). */
    public List<String> getVisibleGroupIds(String environmentId) {
        return getVisibleGroupIds(userService.getCurrentUser(), environmentId);
    }

    /**
     * Group ids in an environment a user can see: every group when they have any
     * environment-wide access (a global role or an ENVIRONMENT grant), otherwise only the
     * groups they hold a GROUP grant on. Empty when they can see nothing.
     */
    public List<String> getVisibleGroupIds(User user, String environmentId) {
        if (user == null) {
            return List.of();
        }
        List<VmGroup> groups = vmGroupRepository.findByEnvironmentId(environmentId);
        if (groups.isEmpty()) {
            return List.of();
        }
        List<String> allGroupIds = groups.stream().map(VmGroup::getGroupId).toList();

        boolean environmentWide = globalRoleLevel(user) != null
                || accessService.getActiveGrant(user.getUserId(), AccessScopeType.ENVIRONMENT, environmentId).isPresent();
        if (environmentWide) {
            return allGroupIds;
        }
        java.util.Set<String> granted = accessService.getActiveGroupGrantLevels(user.getUserId(), allGroupIds).keySet();
        return allGroupIds.stream().filter(granted::contains).toList();
    }

    /**
     * Whether the current user can manage access on a group — resolved to "can manage access
     * on the group's environment". A group whose environment cannot be resolved is denied.
     */
    public boolean canManageGroupAccess(String groupId) {
        return vmGroupRepository.findById(groupId)
                .map(g -> canManageEnvironmentAccess(g.getEnvironment().getEnvironmentId()))
                .orElse(false);
    }

    /**
     * The level a user gets from their global role alone: ADMIN and ENV_ADMIN both act as
     * ADMIN for visibility and operations; every other user gets nothing from their role.
     */
    private AccessLevel globalRoleLevel(User user) {
        if (user != null && (user.isAdmin() || user.isEnvAdmin())) {
            return AccessLevel.ADMIN;
        }
        return null;
    }

    private User loadUser(String userId) {
        if (userId == null) {
            return null;
        }
        try {
            return userService.getUserById(userId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** True when {@code level} is non-null and at least {@code required}. */
    private static boolean atLeast(AccessLevel level, AccessLevel required) {
        return level != null && level.ordinal() >= required.ordinal();
    }

    /** Greater of two nullable levels; null means "no access". */
    private static AccessLevel higher(AccessLevel a, AccessLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}

