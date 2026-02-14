package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Repository for User entity operations.
 */
@Repository
public interface UserRepository extends JpaRepository<User, String> {

    /**
     * Find user by Azure AD object ID (used during OAuth2 login).
     */
    Optional<User> findByAzureAdObjectId(String azureAdObjectId);

    /**
     * Find user by email address.
     */
    Optional<User> findByEmail(String email);

    /**
     * Find all active users.
     */
    List<User> findByIsActiveTrue();

    /**
     * Find all active users ordered by display name.
     */
    List<User> findByIsActiveTrueOrderByDisplayNameAsc();

    /**
     * Find all admin users.
     */
    List<User> findByAdminTrueAndIsActiveTrue();

    /**
     * Find all environment admin users.
     */
    List<User> findByEnvAdminTrueAndIsActiveTrue();

    /**
     * Check if user with email exists.
     */
    boolean existsByEmail(String email);

    /**
     * Check if user with Azure AD object ID exists.
     */
    boolean existsByAzureAdObjectId(String azureAdObjectId);

    /**
     * Update last login timestamp for a user.
     */
    @Modifying
    @Query("UPDATE User u SET u.lastLoginAt = :timestamp WHERE u.userId = :userId")
    void updateLastLoginAt(@Param("userId") String userId, @Param("timestamp") Timestamp timestamp);

    /**
     * Search users by email or display name (case-insensitive).
     */
    @Query("SELECT u FROM User u WHERE u.isActive = true AND " +
           "(LOWER(u.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(u.displayName) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    List<User> searchActiveUsers(@Param("searchTerm") String searchTerm);

    /**
     * Count active admin users.
     */
    long countByAdminTrueAndIsActiveTrue();
}

