package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.LockHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for LockHistory entity operations.
 */
@Repository
public interface LockHistoryRepository extends JpaRepository<LockHistory, String> {

    /**
     * Find history for a specific lock.
     */
    List<LockHistory> findByLockLockIdOrderByPerformedAtDesc(String lockId);

    /**
     * Find history for an environment.
     */
    List<LockHistory> findByEnvironmentIdOrderByPerformedAtDesc(String environmentId);

    /**
     * Find recent history for an environment (limited).
     */
    List<LockHistory> findTop20ByEnvironmentIdOrderByPerformedAtDesc(String environmentId);
}

