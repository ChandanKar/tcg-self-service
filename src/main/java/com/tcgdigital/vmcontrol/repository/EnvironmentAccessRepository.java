package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for EnvironmentAccess entity operations.
 */
@Repository
public interface EnvironmentAccessRepository extends JpaRepository<EnvironmentAccess, String> {

    /**
     * Find active ENVIRONMENT-scoped access for a user on an environment. Group-scoped grants
     * are deliberately excluded — this backs the "does the user have the whole environment"
     * checks.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "WHERE ea.environment.environmentId = :environmentId " +
           "AND ea.user.userId = :userId " +
           "AND ea.scopeType = 'ENVIRONMENT' " +
           "AND ea.status = 'ACTIVE' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now)")
    Optional<EnvironmentAccess> findActiveAccess(
            @Param("environmentId") String environmentId,
            @Param("userId") String userId,
            @Param("now") Timestamp now);

    /**
     * Find all access grants for an environment.
     */
    List<EnvironmentAccess> findByEnvironment_EnvironmentIdAndStatus(String environmentId, AccessStatus status);

    /**
     * Find all active access grants for an environment.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "WHERE ea.environment.environmentId = :environmentId " +
           "AND ea.status = 'ACTIVE' " +
           "ORDER BY ea.user.displayName")
    List<EnvironmentAccess> findActiveAccessByEnvironment(@Param("environmentId") String environmentId);

    /**
     * Find active access grants for an environment with users loaded for recipient resolution.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "JOIN FETCH ea.user " +
           "WHERE ea.environment.environmentId = :environmentId " +
           "AND ea.status = 'ACTIVE' " +
           "ORDER BY ea.user.displayName")
    List<EnvironmentAccess> findActiveAccessWithUsersByEnvironment(@Param("environmentId") String environmentId);

    /**
     * Find all access grants for a user.
     */
    List<EnvironmentAccess> findByUser_UserIdAndStatus(String userId, AccessStatus status);

    /**
     * Find all active access grants for a user.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "WHERE ea.user.userId = :userId " +
           "AND ea.status = 'ACTIVE' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now) " +
           "ORDER BY ea.environment.name")
    List<EnvironmentAccess> findActiveAccessByUser(
            @Param("userId") String userId,
            @Param("now") Timestamp now);

    /**
     * Paginated, optionally name/description-filtered active access grants for a user — backs
     * the server-side-paginated "My Environments" table for non-admin users. A null {@code
     * search} short-circuits the filter, matching the same query plan as the unfiltered case.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "WHERE ea.user.userId = :userId " +
           "AND ea.status = 'ACTIVE' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now) " +
           "AND (:search IS NULL OR LOWER(ea.environment.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "     OR LOWER(ea.environment.description) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY ea.environment.name")
    Page<EnvironmentAccess> searchActiveAccessByUser(
            @Param("userId") String userId,
            @Param("search") String search,
            @Param("now") Timestamp now,
            Pageable pageable);

    /**
     * Find environments where user has at least the specified access level.
     */
    default List<EnvironmentAccess> findByUserWithMinAccessLevel(String userId, AccessLevel minLevel, Timestamp now) {
        return findByUserWithAccessLevelIn(userId, AccessLevel.atLeast(minLevel), now);
    }

    /**
     * Active env-admin users holding an active ENVIRONMENT ADMIN grant on one environment, in one
     * query (E11-T08): notification fan-out no longer runs a grant query per env-admin.
     */
    @Query("SELECT DISTINCT ea.user FROM EnvironmentAccess ea " +
           "WHERE ea.environment.environmentId = :environmentId " +
           "AND ea.scopeType = 'ENVIRONMENT' " +
           "AND ea.status = 'ACTIVE' " +
           "AND ea.accessLevel = 'ADMIN' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now) " +
           "AND ea.user.envAdmin = true AND ea.user.isActive = true")
    List<com.tcgdigital.vmcontrol.model.User> findActiveEnvAdminGrantHolders(@Param("environmentId") String environmentId,
                                                                            @Param("now") Timestamp now);

    /** Levels are matched by list, never with {@code >=}: the column holds enum names (H6). */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "WHERE ea.user.userId = :userId " +
           "AND ea.scopeType = 'ENVIRONMENT' " +
           "AND ea.status = 'ACTIVE' " +
           "AND ea.accessLevel IN :levels " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now)")
    List<EnvironmentAccess> findByUserWithAccessLevelIn(
            @Param("userId") String userId,
            @Param("levels") Collection<AccessLevel> levels,
            @Param("now") Timestamp now);

    /**
     * DISTINCT active environments a user can reach through any grant (ENVIRONMENT or GROUP),
     * optionally name/description-filtered — backs the paginated "My Environments" table so a
     * user with several grants in one environment sees it once.
     */
    @Query(value =
           "SELECT DISTINCT e FROM Environment e JOIN EnvironmentAccess ea ON ea.environment = e " +
           "WHERE ea.user.userId = :userId " +
           "AND ea.status = 'ACTIVE' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now) " +
           "AND e.isActive = true " +
           "AND (:search IS NULL OR LOWER(e.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "     OR LOWER(e.displayName) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "     OR LOWER(e.description) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY e.name",
           countQuery =
           "SELECT COUNT(DISTINCT e.environmentId) FROM Environment e JOIN EnvironmentAccess ea ON ea.environment = e " +
           "WHERE ea.user.userId = :userId " +
           "AND ea.status = 'ACTIVE' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now) " +
           "AND e.isActive = true " +
           "AND (:search IS NULL OR LOWER(e.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "     OR LOWER(e.displayName) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "     OR LOWER(e.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Environment> findDistinctActiveEnvironmentsForUser(
            @Param("userId") String userId,
            @Param("search") String search,
            @Param("now") Timestamp now,
            Pageable pageable);

    // ============= Scope-aware finders (env or group) =============

    /**
     * The user's single active grant on one specific scope. Backs the applyGrant upsert:
     * one active grant per (user, scopeType, scopeId).
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "WHERE ea.user.userId = :userId " +
           "AND ea.scopeType = :scopeType " +
           "AND ea.scopeId = :scopeId " +
           "AND ea.status = 'ACTIVE' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now)")
    Optional<EnvironmentAccess> findActiveByUserAndScope(
            @Param("userId") String userId,
            @Param("scopeType") AccessScopeType scopeType,
            @Param("scopeId") String scopeId,
            @Param("now") Timestamp now);

    /**
     * All active grants on one specific scope, users loaded — "who has access to this group"
     * and recipient resolution.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "JOIN FETCH ea.user " +
           "WHERE ea.scopeType = :scopeType " +
           "AND ea.scopeId = :scopeId " +
           "AND ea.status = 'ACTIVE' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now) " +
           "ORDER BY ea.user.displayName")
    List<EnvironmentAccess> findActiveByScopeTypeAndScopeId(
            @Param("scopeType") AccessScopeType scopeType,
            @Param("scopeId") String scopeId,
            @Param("now") Timestamp now);

    /**
     * A user's active GROUP-scoped grants across a set of group ids — resolves the user's
     * effective level over an environment's groups in one query.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "WHERE ea.user.userId = :userId " +
           "AND ea.scopeType = 'GROUP' " +
           "AND ea.scopeId IN :groupIds " +
           "AND ea.status = 'ACTIVE' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now)")
    List<EnvironmentAccess> findActiveGroupGrantsForUser(
            @Param("userId") String userId,
            @Param("groupIds") Collection<String> groupIds,
            @Param("now") Timestamp now);

    /**
     * Check if user has ENVIRONMENT-scoped access to environment (a group-scoped grant does
     * not count as environment access).
     */
    @Query("SELECT COUNT(ea) > 0 FROM EnvironmentAccess ea " +
           "WHERE ea.environment.environmentId = :environmentId " +
           "AND ea.user.userId = :userId " +
           "AND ea.scopeType = 'ENVIRONMENT' " +
           "AND ea.status = 'ACTIVE' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now)")
    boolean hasAccess(
            @Param("environmentId") String environmentId,
            @Param("userId") String userId,
            @Param("now") Timestamp now);

    /**
     * Check if user has at least the required ENVIRONMENT-scoped access level.
     */
    default boolean hasAccessLevel(String environmentId, String userId, AccessLevel requiredLevel, Timestamp now) {
        return hasAccessLevelIn(environmentId, userId, AccessLevel.atLeast(requiredLevel), now);
    }

    @Query("SELECT COUNT(ea) > 0 FROM EnvironmentAccess ea " +
           "WHERE ea.environment.environmentId = :environmentId " +
           "AND ea.user.userId = :userId " +
           "AND ea.scopeType = 'ENVIRONMENT' " +
           "AND ea.status = 'ACTIVE' " +
           "AND ea.accessLevel IN :levels " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now)")
    boolean hasAccessLevelIn(
            @Param("environmentId") String environmentId,
            @Param("userId") String userId,
            @Param("levels") Collection<AccessLevel> levels,
            @Param("now") Timestamp now);

    /**
     * Grants of a user that ended since {@code since}: revoked, or past their expiry (whether
     * or not the expiry job has flipped them to EXPIRED yet). Backs "Recently expired or
     * revoked" in the My Account panel.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "JOIN FETCH ea.environment " +
           "WHERE ea.user.userId = :userId " +
           "AND ((ea.status = 'REVOKED' AND ea.revokedAt >= :since) " +
           "  OR (ea.status <> 'REVOKED' AND ea.expiresAt IS NOT NULL " +
           "      AND ea.expiresAt <= :now AND ea.expiresAt >= :since))")
    List<EnvironmentAccess> findEndedAccessByUserSince(
            @Param("userId") String userId,
            @Param("now") Timestamp now,
            @Param("since") Timestamp since);

    /**
     * Most recently granted rows for a user, any status — the "access granted" events in the
     * user's own activity feed.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "JOIN FETCH ea.environment " +
           "WHERE ea.user.userId = :userId " +
           "ORDER BY ea.grantedAt DESC")
    List<EnvironmentAccess> findRecentGrantsByUser(@Param("userId") String userId, Pageable pageable);

    /** The user's grants that an admin edited directly, most recent edit first (My Account "Updated"). */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "JOIN FETCH ea.environment " +
           "WHERE ea.user.userId = :userId AND ea.lastModifiedAt IS NOT NULL " +
           "ORDER BY ea.lastModifiedAt DESC")
    List<EnvironmentAccess> findRecentlyModifiedGrantsByUser(@Param("userId") String userId, Pageable pageable);

    /**
     * Find expired access grants that need to be marked as expired.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "WHERE ea.status = 'ACTIVE' " +
           "AND ea.expiresAt IS NOT NULL " +
           "AND ea.expiresAt <= :now")
    List<EnvironmentAccess> findExpiredAccess(@Param("now") Timestamp now);

    /**
     * Ids of active grants past their expiry; each is expired in its own transaction.
     */
    @Query("SELECT ea.accessId FROM EnvironmentAccess ea " +
           "WHERE ea.status = 'ACTIVE' " +
           "AND ea.expiresAt IS NOT NULL " +
           "AND ea.expiresAt <= :now")
    List<String> findExpiredAccessIds(@Param("now") Timestamp now);

    /**
     * Find active access grants expiring inside a warning window.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "JOIN FETCH ea.user " +
           "JOIN FETCH ea.environment " +
           "WHERE ea.status = 'ACTIVE' " +
           "AND ea.expiresAt IS NOT NULL " +
           "AND ea.expiresAt > :start " +
           "AND ea.expiresAt <= :end")
    List<EnvironmentAccess> findAccessExpiringBetweenWithDetails(
            @Param("start") Timestamp start,
            @Param("end") Timestamp end);

    /**
     * Count active users with access to an environment.
     */
    @Query("SELECT COUNT(ea) FROM EnvironmentAccess ea " +
           "WHERE ea.environment.environmentId = :environmentId " +
           "AND ea.status = 'ACTIVE'")
    long countActiveAccessByEnvironment(@Param("environmentId") String environmentId);

    /** Grants on one scope in a status, expired or not (group delete revokes every ACTIVE one, M2). */
    List<EnvironmentAccess> findByScopeTypeAndScopeIdAndStatus(AccessScopeType scopeType, String scopeId,
                                                               com.tcgdigital.vmcontrol.model.AccessStatus status);
}
