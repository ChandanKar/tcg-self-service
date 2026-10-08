package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for security and authorization checks.
 *
 * <p>Contract for controllers and services: call {@link #assertCanView}, {@link #assertCanOperate}
 * or {@link #assertCanAdminister} for the environment in the request path (403 when denied), and
 * {@link #assertSameEnvironment} when a path also names a child resource (404 when it belongs to
 * another environment, so ids elsewhere are not confirmed). Levels are compared by rank, never
 * by name.
 */
@Service
public class SecurityService {

    private static final Logger log = LoggerFactory.getLogger(SecurityService.class);

    /** ENV_ADMIN scope: "global" (every environment) or "assigned" (needs an ENVIRONMENT ADMIN grant). */
    static final String ENV_ADMIN_SCOPE_GLOBAL = "global";

    private final UserService userService;
    private final EnvironmentAccessService accessService;
    private final VmGroupRepository vmGroupRepository;
    private final String envAdminScope;

    public SecurityService(UserService userService, EnvironmentAccessService accessService,
                           VmGroupRepository vmGroupRepository,
                           @Value("${security.env-admin.scope:global}") String envAdminScope) {
        this.userService = userService;
        this.accessService = accessService;
        this.vmGroupRepository = vmGroupRepository;
        this.envAdminScope = envAdminScope;
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
     * Check if the current user can manage access for an environment: same rule as
     * {@link #canAdministerEnvironment(String)}.
     */
    public boolean canManageEnvironmentAccess(String environmentId) {
        return canAdministerEnvironment(environmentId);
    }

    /**
     * Whether the current user administers an environment (grants, settings): a global ADMIN; an
     * ENV_ADMIN everywhere when security.env-admin.scope=global, otherwise only where they hold an
     * active ENVIRONMENT ADMIN grant; any other user with an active ENVIRONMENT ADMIN grant.
     */
    public boolean canAdministerEnvironment(String environmentId) {
        User user = userService.getCurrentUser();
        if (user == null || environmentId == null) {
            return false;
        }
        if (user.isAdmin()) {
            return true;
        }
        if (user.isEnvAdmin() && ENV_ADMIN_SCOPE_GLOBAL.equalsIgnoreCase(envAdminScope)) {
            return true;
        }
        return atLeast(environmentGrantLevel(user, environmentId), AccessLevel.ADMIN);
    }

    /** True when {@code userId} is the signed-in user (for SpEL: @securityService.isCurrentUser(#userId)). */
    public boolean isCurrentUser(String userId) {
        User user = userService.getCurrentUser();
        return user != null && userId != null && userId.equals(user.getUserId());
    }

    // ============= Assertions (throw instead of returning false) =============

    /** 403 unless the current user can see some part of the environment. */
    public void assertCanView(String environmentId) {
        if (!canViewEnvironment(environmentId)) {
            throw new UnauthorizedException("You do not have access to this environment");
        }
    }

    /** 403 unless the current user can start, stop or lock something in the environment. */
    public void assertCanOperate(String environmentId) {
        if (!canOperateInEnvironment(environmentId)) {
            throw new UnauthorizedException("You cannot start, stop or lock in this environment");
        }
    }

    /** 403 unless the current user administers the environment. */
    public void assertCanAdminister(String environmentId) {
        if (!canAdministerEnvironment(environmentId)) {
            throw new UnauthorizedException("You do not administer this environment");
        }
    }

    /**
     * 404 unless a child resource (group, VM, lock...) really belongs to the environment named in
     * the request path, so ids from other environments are neither used nor confirmed.
     */
    public void assertSameEnvironment(String actualEnvironmentId, String pathEnvironmentId) {
        if (actualEnvironmentId == null || !actualEnvironmentId.equals(pathEnvironmentId)) {
            throw new ResourceNotFoundException("Resource not found in environment " + pathEnvironmentId);
        }
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

    private AccessLevel environmentGrantLevel(User user, String environmentId) {
        return accessService.getActiveGrant(user.getUserId(), AccessScopeType.ENVIRONMENT, environmentId)
                .map(EnvironmentAccess::getAccessLevel)
                .orElse(null);
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

