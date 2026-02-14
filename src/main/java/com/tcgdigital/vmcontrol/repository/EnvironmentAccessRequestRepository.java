package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AccessRequestStatus;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for EnvironmentAccessRequest entity operations.
 */
@Repository
public interface EnvironmentAccessRequestRepository extends JpaRepository<EnvironmentAccessRequest, String> {

    /**
     * Find all pending requests for an environment.
     */
    List<EnvironmentAccessRequest> findByEnvironment_EnvironmentIdAndStatusOrderByCreatedAtDesc(
            String environmentId, AccessRequestStatus status);

    /**
     * Find all requests by requester.
     */
    List<EnvironmentAccessRequest> findByRequester_UserIdOrderByCreatedAtDesc(String userId);

    /**
     * Find all requests by requester with specific status.
     */
    List<EnvironmentAccessRequest> findByRequester_UserIdAndStatusOrderByCreatedAtDesc(
            String userId, AccessRequestStatus status);

    /**
     * Find all pending requests (for admins to review).
     */
    List<EnvironmentAccessRequest> findByStatusOrderByCreatedAtAsc(AccessRequestStatus status);

    /**
     * Find pending requests for environments where user is env_admin.
     * This requires joining with EnvironmentAccess to check admin access.
     */
    @Query("SELECT ear FROM EnvironmentAccessRequest ear " +
           "WHERE ear.status = 'PENDING' " +
           "AND (EXISTS (SELECT 1 FROM EnvironmentAccess ea " +
           "             WHERE ea.environment = ear.environment " +
           "             AND ea.user.userId = :reviewerUserId " +
           "             AND ea.accessLevel = 'ADMIN' " +
           "             AND ea.status = 'ACTIVE') " +
           "     OR EXISTS (SELECT 1 FROM User u " +
           "                WHERE u.userId = :reviewerUserId " +
           "                AND (u.admin = true OR u.envAdmin = true))) " +
           "ORDER BY ear.createdAt ASC")
    List<EnvironmentAccessRequest> findPendingRequestsForReviewer(@Param("reviewerUserId") String reviewerUserId);

    /**
     * Check if user has a pending request for an environment.
     */
    @Query("SELECT COUNT(ear) > 0 FROM EnvironmentAccessRequest ear " +
           "WHERE ear.environment.environmentId = :environmentId " +
           "AND ear.requester.userId = :userId " +
           "AND ear.status = 'PENDING'")
    boolean hasPendingRequest(
            @Param("environmentId") String environmentId,
            @Param("userId") String userId);

    /**
     * Find recent requests (last 30 days) for audit/history purposes.
     */
    @Query("SELECT ear FROM EnvironmentAccessRequest ear " +
           "WHERE ear.environment.environmentId = :environmentId " +
           "ORDER BY ear.createdAt DESC")
    List<EnvironmentAccessRequest> findRecentRequestsByEnvironment(
            @Param("environmentId") String environmentId);

    /**
     * Count pending requests.
     */
    long countByStatus(AccessRequestStatus status);

    /**
     * Count pending requests for a specific environment.
     */
    long countByEnvironment_EnvironmentIdAndStatus(String environmentId, AccessRequestStatus status);
}

