package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Vm entity operations.
 */
@Repository
public interface VmRepository extends JpaRepository<Vm, String> {

    /**
     * Find all VMs in a group ordered by sequence position.
     */
    List<Vm> findByGroupGroupIdOrderBySequencePositionAsc(String groupId);

    /**
     * Find VM by name within a group.
     */
    Optional<Vm> findByGroupGroupIdAndName(String groupId, String name);

    /**
     * Check if sequence position exists in group.
     */
    boolean existsByGroupGroupIdAndSequencePosition(String groupId, Integer sequencePosition);

    /**
     * Check if name exists in group.
     */
    boolean existsByGroupGroupIdAndName(String groupId, String name);

    /**
     * Check if provider VM ID already registered.
     */
    boolean existsByProviderAndProviderVmId(com.tcgdigital.vmcontrol.model.CloudProvider provider, String providerVmId);

    /**
     * Find all VMs in an environment (via group).
     */
    @Query("SELECT v FROM Vm v WHERE v.group.environment.environmentId = :environmentId ORDER BY v.group.sequencePosition, v.sequencePosition")
    List<Vm> findByEnvironmentId(String environmentId);

    /**
     * Find all VMs in a group.
     */
    @Query("SELECT v FROM Vm v WHERE v.group.groupId = :groupId")
    List<Vm> findByGroupId(String groupId);

    /**
     * Find VMs by status.
     */
    List<Vm> findByStatus(VmStatus status);

    /**
     * Find VMs with state drift detected.
     */
    List<Vm> findByStateDriftDetectedTrue();

    /**
     * Count VMs in a group.
     */
    long countByGroupGroupId(String groupId);

    /**
     * Count running VMs in a group.
     */
    long countByGroupGroupIdAndStatus(String groupId, VmStatus status);

    /**
     * Find all active VMs for state sync.
     */
    @Query("SELECT v FROM Vm v WHERE v.group.environment.isActive = true ORDER BY v.group.environment.environmentId, v.group.sequencePosition, v.sequencePosition")
    List<Vm> findByIsActiveTrue();

    /**
     * Get all registered provider VM IDs for a given cloud provider (globally across all environments).
     * Used for cross-environment duplicate detection in the EC2 picker.
     */
    @Query("SELECT v.providerVmId FROM Vm v WHERE v.provider = :provider")
    List<String> findAllProviderVmIdsByProvider(com.tcgdigital.vmcontrol.model.CloudProvider provider);
}
