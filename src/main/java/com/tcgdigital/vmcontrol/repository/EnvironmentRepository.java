package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.Environment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Environment entity operations.
 */
@Repository
public interface EnvironmentRepository extends JpaRepository<Environment, String> {

    /**
     * Find environment by name.
     */
    Optional<Environment> findByName(String name);

    /**
     * Find all active environments.
     */
    List<Environment> findByIsActiveTrue();

    /**
     * Find all active environments ordered by name.
     */
    List<Environment> findByIsActiveTrueOrderByNameAsc();

    /**
     * Check if environment name already exists.
     */
    boolean existsByName(String name);

    /**
     * Find environment with groups eagerly loaded.
     */
    @Query("SELECT e FROM Environment e LEFT JOIN FETCH e.groups WHERE e.environmentId = :environmentId")
    Optional<Environment> findByIdWithGroups(String environmentId);

    /**
     * Find all active EKS environments for sync scheduling.
     */
    @Query("SELECT e FROM Environment e WHERE e.serviceType = 'EKS' AND e.isActive = true ORDER BY e.name ASC")
    List<Environment> findActiveEksEnvironments();

    /**
     * Find all active EC2 environments for VM discovery.
     */
    @Query("SELECT e FROM Environment e WHERE e.serviceType = 'EC2' AND e.isActive = true ORDER BY e.name ASC")
    List<Environment> findActiveEc2Environments();
}

