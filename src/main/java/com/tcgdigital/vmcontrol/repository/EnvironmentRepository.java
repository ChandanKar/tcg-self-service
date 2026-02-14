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
}

