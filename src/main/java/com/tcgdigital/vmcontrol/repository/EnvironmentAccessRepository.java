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
           VISIBLE_RUNNING_FILTER + EnvironmentRepository.REGION_FILTER +
           "ORDER BY e.name",
           countQuery =
           "SELECT COUNT(DISTINCT e.environmentId) FROM Environment e JOIN EnvironmentAccess ea ON ea.environment = e " +
           "WHERE ea.user.userId = :userId " +
           "AND ea.status = 'ACTIVE' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now) " +
           "AND e.isActive = true " +
           "AND (:search IS NULL OR LOWER(e.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "     OR LOWER(e.displayName) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "     OR LOWER(e.description) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           VISIBLE_RUNNING_FILTER + EnvironmentRepository.REGION_FILTER)
    Page<Environment> findDistinctActiveEnvironmentsForUser(
            @Param("userId") String userId,
            @Param("search") String search,
            @Param("now") Timestamp now,
            @Param("running") Boolean running,
            @Param("region") String region,
            Pageable pageable);

    default Page<Environment> findDistinctActiveEnvironmentsForUser(String userId, String search, Timestamp now,
                                                                    Boolean running, Pageable pageable) {
        return findDistinctActiveEnvironmentsForUser(userId, search, now, running, null, pageable);
    }

    default Page<Environment> findDistinctActiveEnvironmentsForUser(String userId, String search, Timestamp now,
                                                                    Pageable pageable) {
        return findDistinctActiveEnvironmentsForUser(userId, search, now, null, pageable);
    }

    /**
     * The list's Running / Stopped filter for a user (U06-T04): only VMs the user can see count —
     * every VM under an environment-wide grant, else those in groups they hold a grant on — so a
     * hidden group's state never shows through the filter.
     */
    String VISIBLE_RUNNING_VM =
           "SELECT 1 FROM Vm v WHERE v.group.environment = e AND v.isActive = true AND v.status = 'RUNNING' " +
           "AND EXISTS (SELECT 1 FROM EnvironmentAccess g WHERE g.user.userId = :userId AND g.environment = e " +
           "    AND g.status = 'ACTIVE' AND (g.expiresAt IS NULL OR g.expiresAt > :now) " +
           "    AND (g.scopeType = 'ENVIRONMENT' OR g.scopeId = v.group.groupId))";

    String VISIBLE_RUNNING_FILTER =
           "AND (:running IS NULL " +
           "     OR (:running = true AND EXISTS (" + VISIBLE_RUNNING_VM + ")) " +
           "     OR (:running = false AND NOT EXISTS (" + VISIBLE_RUNNING_VM + "))) ";

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

    /** Grants on one scope in a status, expired or not (group delete revokes every ACTIVE one, M2). */
    List<EnvironmentAccess> findByScopeTypeAndScopeIdAndStatus(AccessScopeType scopeType, String scopeId,
                                                               com.tcgdigital.vmcontrol.model.AccessStatus status);
    /**
     * Grants across the environments a reviewer may manage (U10-T01), by status bucket: ACTIVE
     * (in force now), ENDING (in force and ending by {@code soon}), ENDED (revoked or expired since
     * {@code since}). {@code restricted=false} means every environment ({@code allowed} is then
     * ignored but must not be empty). {@code q} is a lower-case LIKE pattern or null. ENDING is
     * ordered by end, ENDED by when it ended (newest first), ACTIVE by person.
     */
    @Query(value = "SELECT ea FROM EnvironmentAccess ea JOIN FETCH ea.user JOIN FETCH ea.environment LEFT JOIN FETCH ea.grantedBy " +
           "WHERE (:restricted = false OR ea.environment.environmentId IN :allowed) " +
           "AND (:environmentId IS NULL OR ea.environment.environmentId = :environmentId) " +
           "AND ((:bucket = 'ACTIVE' AND ea.status = 'ACTIVE' AND (ea.expiresAt IS NULL OR ea.expiresAt > :now)) " +
           "  OR (:bucket = 'ENDING' AND ea.status = 'ACTIVE' AND ea.expiresAt > :now AND ea.expiresAt <= :soon) " +
           "  OR (:bucket = 'ENDED' AND ((ea.status = 'REVOKED' AND ea.revokedAt >= :since) " +
           "      OR (ea.status <> 'REVOKED' AND ea.expiresAt IS NOT NULL AND ea.expiresAt <= :now AND ea.expiresAt >= :since)))) " +
           "AND (:q IS NULL OR LOWER(ea.user.displayName) LIKE :q OR LOWER(ea.user.email) LIKE :q " +
           "  OR LOWER(ea.environment.displayName) LIKE :q OR LOWER(ea.environment.name) LIKE :q " +
           "  OR (ea.scopeType = 'GROUP' AND EXISTS (SELECT 1 FROM VmGroup g WHERE g.groupId = ea.scopeId AND LOWER(g.displayName) LIKE :q))) " +
           "ORDER BY CASE WHEN :bucket = 'ENDING' THEN ea.expiresAt END ASC, " +
           "  CASE WHEN :bucket = 'ENDED' THEN COALESCE(ea.revokedAt, ea.expiresAt) END DESC, " +
           "  ea.user.displayName ASC, ea.environment.displayName ASC",
           countQuery = "SELECT COUNT(ea) FROM EnvironmentAccess ea " +
           "WHERE (:restricted = false OR ea.environment.environmentId IN :allowed) " +
           "AND (:environmentId IS NULL OR ea.environment.environmentId = :environmentId) " +
           "AND ((:bucket = 'ACTIVE' AND ea.status = 'ACTIVE' AND (ea.expiresAt IS NULL OR ea.expiresAt > :now)) " +
           "  OR (:bucket = 'ENDING' AND ea.status = 'ACTIVE' AND ea.expiresAt > :now AND ea.expiresAt <= :soon) " +
           "  OR (:bucket = 'ENDED' AND ((ea.status = 'REVOKED' AND ea.revokedAt >= :since) " +
           "      OR (ea.status <> 'REVOKED' AND ea.expiresAt IS NOT NULL AND ea.expiresAt <= :now AND ea.expiresAt >= :since)))) " +
           "AND (:q IS NULL OR LOWER(ea.user.displayName) LIKE :q OR LOWER(ea.user.email) LIKE :q " +
           "  OR LOWER(ea.environment.displayName) LIKE :q OR LOWER(ea.environment.name) LIKE :q " +
           "  OR (ea.scopeType = 'GROUP' AND EXISTS (SELECT 1 FROM VmGroup g WHERE g.groupId = ea.scopeId AND LOWER(g.displayName) LIKE :q)))")
    Page<EnvironmentAccess> searchGrants(
            @Param("restricted") boolean restricted,
            @Param("allowed") Collection<String> allowed,
            @Param("environmentId") String environmentId,
            @Param("bucket") String bucket,
            @Param("now") Timestamp now,
            @Param("soon") Timestamp soon,
            @Param("since") Timestamp since,
            @Param("q") String q,
            Pageable pageable);

    /** Grants in force now in the reviewer's environments, counted per access level (U10-T01). */
    @Query("SELECT ea.accessLevel, COUNT(ea) FROM EnvironmentAccess ea " +
           "WHERE (:restricted = false OR ea.environment.environmentId IN :allowed) " +
           "AND (:environmentId IS NULL OR ea.environment.environmentId = :environmentId) " +
           "AND ea.status = 'ACTIVE' AND (ea.expiresAt IS NULL OR ea.expiresAt > :now) " +
           "GROUP BY ea.accessLevel")
    List<Object[]> countActiveByLevel(
            @Param("restricted") boolean restricted,
            @Param("allowed") Collection<String> allowed,
            @Param("environmentId") String environmentId,
            @Param("now") Timestamp now);

    /** Grants in force now that end by {@code soon}, in the reviewer's environments (U10-T01). */
    @Query("SELECT COUNT(ea) FROM EnvironmentAccess ea " +
           "WHERE (:restricted = false OR ea.environment.environmentId IN :allowed) " +
           "AND (:environmentId IS NULL OR ea.environment.environmentId = :environmentId) " +
           "AND ea.status = 'ACTIVE' AND ea.expiresAt > :now AND ea.expiresAt <= :soon")
    long countEnding(
            @Param("restricted") boolean restricted,
            @Param("allowed") Collection<String> allowed,
            @Param("environmentId") String environmentId,
            @Param("now") Timestamp now,
            @Param("soon") Timestamp soon);
}
