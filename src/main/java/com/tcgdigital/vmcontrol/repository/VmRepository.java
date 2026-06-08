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
     * Find all active VMs in a group ordered by sequence position.
     */
    @Query("SELECT v FROM Vm v WHERE v.group.groupId = :groupId AND v.isActive = true ORDER BY v.sequencePosition ASC")
    List<Vm> findByGroupGroupIdOrderBySequencePositionAsc(String groupId);

    /**
     * Find all VMs in a group (including inactive) ordered by sequence position.
     * Use for admin/reporting purposes.
     */
    @Query("SELECT v FROM Vm v WHERE v.group.groupId = :groupId ORDER BY v.sequencePosition ASC")
    List<Vm> findAllByGroupGroupIdOrderBySequencePositionAsc(String groupId);

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
     * Find all active VMs in an environment (via group).
     */
    @Query("SELECT v FROM Vm v WHERE v.group.environment.environmentId = :environmentId AND v.isActive = true ORDER BY v.group.sequencePosition, v.sequencePosition")
    List<Vm> findByEnvironmentId(String environmentId);

    /**
     * Find all VMs in an environment including inactive (for admin/reporting).
     */
    @Query("SELECT v FROM Vm v WHERE v.group.environment.environmentId = :environmentId ORDER BY v.group.sequencePosition, v.sequencePosition")
    List<Vm> findAllByEnvironmentId(String environmentId);

    /**
     * Find all active VMs in a group.
     */
    @Query("SELECT v FROM Vm v WHERE v.group.groupId = :groupId AND v.isActive = true")
    List<Vm> findByGroupId(String groupId);

    /**
     * Find VMs by status (only active VMs).
     */
    @Query("SELECT v FROM Vm v WHERE v.status = :status AND v.isActive = true")
    List<Vm> findByStatus(VmStatus status);

    /**
     * Find VMs with state drift detected (only active VMs).
     */
    @Query("SELECT v FROM Vm v WHERE v.stateDriftDetected = true AND v.isActive = true")
    List<Vm> findByStateDriftDetectedTrue();

    /**
     * Find inactive VMs (for admin review).
     */
    @Query("SELECT v FROM Vm v WHERE v.isActive = false ORDER BY v.updatedAt DESC")
    List<Vm> findInactiveVms();

    /**
     * Count active VMs in a group.
     */
    @Query("SELECT COUNT(v) FROM Vm v WHERE v.group.groupId = :groupId AND v.isActive = true")
    long countByGroupGroupId(String groupId);

    /**
     * Count active running VMs in a group.
     */
    @Query("SELECT COUNT(v) FROM Vm v WHERE v.group.groupId = :groupId AND v.status = :status AND v.isActive = true")
    long countByGroupGroupIdAndStatus(String groupId, VmStatus status);

    /**
     * Find all active VMs for state sync.
     * Only returns VMs where both the VM and its environment are active.
     */
    @Query("SELECT v FROM Vm v WHERE v.isActive = true AND v.group.environment.isActive = true ORDER BY v.group.environment.environmentId, v.group.sequencePosition, v.sequencePosition")
    List<Vm> findByIsActiveTrue();

    /**
     * Get all registered provider VM IDs for a given cloud provider (globally across all environments).
     * Used for cross-environment duplicate detection in the EC2 picker.
     */
    @Query("SELECT v.providerVmId FROM Vm v WHERE v.provider = :provider")
    List<String> findAllProviderVmIdsByProvider(com.tcgdigital.vmcontrol.model.CloudProvider provider);

}
