package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Repository for EnvironmentAccess entity operations.
 */
@Repository
public interface EnvironmentAccessRepository extends JpaRepository<EnvironmentAccess, String> {

    /**
     * Find active access for a user on an environment.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "WHERE ea.environment.environmentId = :environmentId " +
           "AND ea.user.userId = :userId " +
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
     * Find environments where user has at least the specified access level.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "WHERE ea.user.userId = :userId " +
           "AND ea.status = 'ACTIVE' " +
           "AND ea.accessLevel >= :minLevel " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now)")
    List<EnvironmentAccess> findByUserWithMinAccessLevel(
            @Param("userId") String userId,
            @Param("minLevel") AccessLevel minLevel,
            @Param("now") Timestamp now);

    /**
     * Check if user has access to environment.
     */
    @Query("SELECT COUNT(ea) > 0 FROM EnvironmentAccess ea " +
           "WHERE ea.environment.environmentId = :environmentId " +
           "AND ea.user.userId = :userId " +
           "AND ea.status = 'ACTIVE' " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now)")
    boolean hasAccess(
            @Param("environmentId") String environmentId,
            @Param("userId") String userId,
            @Param("now") Timestamp now);

    /**
     * Check if user has at least the required access level.
     */
    @Query("SELECT COUNT(ea) > 0 FROM EnvironmentAccess ea " +
           "WHERE ea.environment.environmentId = :environmentId " +
           "AND ea.user.userId = :userId " +
           "AND ea.status = 'ACTIVE' " +
           "AND ea.accessLevel >= :requiredLevel " +
           "AND (ea.expiresAt IS NULL OR ea.expiresAt > :now)")
    boolean hasAccessLevel(
            @Param("environmentId") String environmentId,
            @Param("userId") String userId,
            @Param("requiredLevel") AccessLevel requiredLevel,
            @Param("now") Timestamp now);

    /**
     * Find expired access grants that need to be marked as expired.
     */
    @Query("SELECT ea FROM EnvironmentAccess ea " +
           "WHERE ea.status = 'ACTIVE' " +
           "AND ea.expiresAt IS NOT NULL " +
           "AND ea.expiresAt <= :now")
    List<EnvironmentAccess> findExpiredAccess(@Param("now") Timestamp now);

    /**
     * Count active users with access to an environment.
     */
    @Query("SELECT COUNT(ea) FROM EnvironmentAccess ea " +
           "WHERE ea.environment.environmentId = :environmentId " +
           "AND ea.status = 'ACTIVE'")
    long countActiveAccessByEnvironment(@Param("environmentId") String environmentId);
}

