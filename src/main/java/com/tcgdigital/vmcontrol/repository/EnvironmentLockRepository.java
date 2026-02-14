package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.EnvironmentLock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/**
 * Repository for EnvironmentLock entity operations.
 */
@Repository
public interface EnvironmentLockRepository extends JpaRepository<EnvironmentLock, String> {

    /**
     * Find active lock for an environment.
     */
    Optional<EnvironmentLock> findByEnvironmentEnvironmentIdAndIsActiveTrue(String environmentId);

    /**
     * Find active lock with pessimistic lock for concurrent access control.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM EnvironmentLock l WHERE l.environment.environmentId = :environmentId AND l.isActive = true")
    Optional<EnvironmentLock> findByEnvironmentIdWithLock(String environmentId);

    /**
     * Find all active locks.
     */
    List<EnvironmentLock> findByIsActiveTrue();

    /**
     * Find all locks for an environment (for history).
     */
    List<EnvironmentLock> findByEnvironmentEnvironmentIdOrderByLockedAtDesc(String environmentId);

    /**
     * Find locks held by a specific user.
     */
    List<EnvironmentLock> findByLockedByUserIdAndIsActiveTrue(String userId);

    /**
     * Check if environment has an active lock.
     */
    boolean existsByEnvironmentEnvironmentIdAndIsActiveTrue(String environmentId);
}

