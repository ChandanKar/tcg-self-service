package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for VmGroup entity operations.
 */
@Repository
public interface VmGroupRepository extends JpaRepository<VmGroup, String> {

    /**
     * Find all groups for an environment ordered by sequence position.
     */
    List<VmGroup> findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(String environmentId);

    /**
     * Find group by name within an environment.
     */
    Optional<VmGroup> findByEnvironmentEnvironmentIdAndName(String environmentId, String name);

    /**
     * Check if sequence position exists in environment.
     */
    boolean existsByEnvironmentEnvironmentIdAndSequencePosition(String environmentId, Integer sequencePosition);

    /**
     * Check if name exists in environment.
     */
    boolean existsByEnvironmentEnvironmentIdAndName(String environmentId, String name);

    /**
     * Find group with VMs eagerly loaded.
     */
    @Query("SELECT g FROM VmGroup g LEFT JOIN FETCH g.vms WHERE g.groupId = :groupId")
    Optional<VmGroup> findByIdWithVms(String groupId);

    /**
     * Find all groups in environment (for dependency validation).
     */
    @Query("SELECT g FROM VmGroup g WHERE g.environment.environmentId = :environmentId")
    List<VmGroup> findByEnvironmentId(String environmentId);

    /**
     * Group counts across many environments in a single query — avoids querying each
     * environment individually when building an environment listing.
     */
    @Query("SELECT g.environment.environmentId AS environmentId, COUNT(g) AS total " +
           "FROM VmGroup g WHERE g.environment.environmentId IN :environmentIds " +
           "GROUP BY g.environment.environmentId")
    List<EnvironmentGroupCounts> countGroupsGroupedByEnvironment(@Param("environmentIds") List<String> environmentIds);

    interface EnvironmentGroupCounts {
        String getEnvironmentId();
        long getTotal();
    }
}

