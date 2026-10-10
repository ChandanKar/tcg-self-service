package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Collection;
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
     * Find a VM by cloud provider type and provider-assigned ID.
     * Used by EksCloudProviderService to look up metadata before scale operations.
     */
    Optional<Vm> findByProviderAndProviderVmId(com.tcgdigital.vmcontrol.model.CloudProvider provider, String providerVmId);

    /**
     * Find all active VMs in an environment (via group).
     */
    @Query("SELECT v FROM Vm v WHERE v.group.environment.environmentId = :environmentId AND v.isActive = true ORDER BY v.group.sequencePosition, v.sequencePosition")
    List<Vm> findByEnvironmentId(String environmentId);

    /** Active VMs of an environment with group and environment loaded (sync, no transaction). */
    @Query("SELECT v FROM Vm v JOIN FETCH v.group g JOIN FETCH g.environment e " +
           "WHERE e.environmentId = :environmentId AND v.isActive = true ORDER BY g.sequencePosition, v.sequencePosition")
    List<Vm> findByEnvironmentIdFetchGroupAndEnvironment(@Param("environmentId") String environmentId);

    /**
     * Find all active VMs across many environments in a single query — avoids querying each
     * environment individually when aggregating VMs for several environments at once.
     */
    @Query("SELECT v FROM Vm v WHERE v.group.environment.environmentId IN :environmentIds AND v.isActive = true")
    List<Vm> findByEnvironmentIdIn(@Param("environmentIds") List<String> environmentIds);

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
     * Highest sequence position in a group over ALL rows, active and inactive: the unique index
     * idx_vm_group_sequence covers inactive rows too, so new positions must start above it (H8).
     */
    @Query("SELECT MAX(v.sequencePosition) FROM Vm v WHERE v.group.groupId = :groupId")
    Integer findMaxSequencePositionByGroupId(@Param("groupId") String groupId);

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
     * Active VMs of active environments for state sync, with group and environment loaded:
     * sync reads the environment on worker threads with no session (H23).
     */
    @Query("SELECT v FROM Vm v JOIN FETCH v.group g JOIN FETCH g.environment e " +
           "WHERE v.isActive = true AND e.isActive = true ORDER BY e.environmentId, g.sequencePosition, v.sequencePosition")
    List<Vm> findActiveForSync();

    /** One VM with group and environment loaded (single-VM sync). */
    @Query("SELECT v FROM Vm v JOIN FETCH v.group g JOIN FETCH g.environment e WHERE v.vmId = :vmId")
    Optional<Vm> findByIdFetchGroupAndEnvironment(@Param("vmId") String vmId);

    /**
     * Get all registered provider VM IDs for a given cloud provider (globally across all environments).
     * Used for cross-environment duplicate detection in the EC2 picker.
     */
    @Query("SELECT v.providerVmId FROM Vm v WHERE v.provider = :provider")
    List<String> findAllProviderVmIdsByProvider(com.tcgdigital.vmcontrol.model.CloudProvider provider);

    /**
     * Paginated active VMs for a single group, for scalable VM listing UIs.
     */
    Page<Vm> findByGroupGroupIdAndIsActiveTrueOrderBySequencePositionAsc(String groupId, Pageable pageable);

    /**
     * All active VMs (in active environments) with group and environment eagerly fetched, for
     * fleet-wide reporting (Cost Management) that needs every VM's environment/group display name
     * without an N+1 lazy-load per distinct group.
     */
    @Query("SELECT v FROM Vm v JOIN FETCH v.group g JOIN FETCH g.environment e " +
           "WHERE v.isActive = true AND e.isActive = true")
    List<Vm> findByIsActiveTrueFetchGroupAndEnvironment();

    /**
     * One environment's active VMs with group and environment loaded; with onlyUntagged, only
     * those never tagged (tag reconciliation after discovery, not the whole fleet).
     */
    @Query("SELECT v FROM Vm v JOIN FETCH v.group g JOIN FETCH g.environment e " +
           "WHERE e.environmentId = :envId AND v.isActive = true AND (:onlyUntagged = false OR v.tagsSyncedAt IS NULL)")
    List<Vm> findActiveByEnvironmentIdFetchGroupAndEnvironment(@Param("envId") String envId,
                                                               @Param("onlyUntagged") boolean onlyUntagged);

    /**
     * Counts for the cost-allocation tagging status banner: how many active VMs have never been
     * successfully tagged yet (tagsSyncedAt IS NULL) vs. how many have.
     */
    long countByIsActiveTrueAndTagsSyncedAtIsNull();

    long countByIsActiveTrueAndTagsSyncedAtIsNotNull();

    /**
     * Per-group VM/running counts for every group in an environment, in a single query —
     * avoids querying each group individually when building a group listing with counts.
     */
    @Query("SELECT v.group.groupId AS groupId, COUNT(v) AS total, " +
           "SUM(CASE WHEN v.status = :runningStatus THEN 1L ELSE 0L END) AS running " +
           "FROM Vm v WHERE v.group.environment.environmentId = :environmentId AND v.isActive = true " +
           "GROUP BY v.group.groupId")
    List<GroupVmCounts> countVmsGroupedByGroup(@Param("environmentId") String environmentId,
                                                @Param("runningStatus") VmStatus runningStatus);

    /**
     * Per-environment VM/running counts across many environments in a single query —
     * avoids querying each environment individually when building an environment listing.
     */
    /** A VM's environment id, without loading the lazy group (per-VM operation audit, E11-T03). */
    @Query("SELECT v.group.environment.environmentId FROM Vm v WHERE v.vmId = :vmId")
    Optional<String> findEnvironmentIdByVmId(@Param("vmId") String vmId);

    @Query("SELECT v.group.environment.environmentId AS environmentId, COUNT(v) AS total, " +
           "SUM(CASE WHEN v.status = :runningStatus THEN 1L ELSE 0L END) AS running " +
           "FROM Vm v WHERE v.group.environment.environmentId IN :environmentIds AND v.isActive = true " +
           "GROUP BY v.group.environment.environmentId")
    List<EnvironmentVmCounts> countVmsGroupedByEnvironment(@Param("environmentIds") List<String> environmentIds,
                                                            @Param("runningStatus") VmStatus runningStatus);

    /**
     * Distinct regions in use per environment, one row per (environment, region) pair — avoids
     * querying each environment individually when building an environment listing with regions.
     * A VM's region is set the same way for EC2 and EKS (an EKS node group's Vm carries the
     * cluster's region), so this derives a consistent answer for either service type without
     * needing to special-case EKS's region metadata.
     */
    @Query("SELECT DISTINCT v.group.environment.environmentId AS environmentId, v.region AS region " +
           "FROM Vm v WHERE v.group.environment.environmentId IN :environmentIds AND v.isActive = true " +
           "AND v.region IS NOT NULL AND v.region <> ''")
    List<EnvironmentRegion> findDistinctRegionsGroupedByEnvironment(@Param("environmentIds") List<String> environmentIds);

    interface GroupVmCounts {
        String getGroupId();
        long getTotal();
        long getRunning();
    }

    interface EnvironmentVmCounts {
        String getEnvironmentId();
        long getTotal();
        long getRunning();
    }

    interface EnvironmentRegion {
        String getEnvironmentId();
        String getRegion();
    }

    // ============= Targeted writes (E05-T04, H14) =============
    // Never clear the persistence context: callers in a surrounding transaction keep their
    // loaded VMs (and must not modify them after one of these updates).

    /** The VM's status as stored now (no entity load). */
    @Query("SELECT v.status FROM Vm v WHERE v.vmId = :vmId")
    Optional<VmStatus> findStatusById(@Param("vmId") String vmId);

    /** Set the status only if it is still {@code expectedStatus}; 0 when someone changed it first. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.status = :newStatus, v.lastStateSyncAt = :syncedAt, v.updatedAt = :syncedAt, " +
           "v.version = v.version + 1 WHERE v.vmId = :vmId AND v.status = :expectedStatus")
    int updateStatusIfCurrent(@Param("vmId") String vmId,
                              @Param("expectedStatus") VmStatus expectedStatus,
                              @Param("newStatus") VmStatus newStatus,
                              @Param("syncedAt") Timestamp syncedAt);

    /** A state sync's drift write: status, active flag and drift flag, only if the status is unchanged. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.status = :newStatus, v.isActive = :active, v.stateDriftDetected = :drift, " +
           "v.lastStateSyncAt = :syncedAt, v.updatedAt = :syncedAt, v.version = v.version + 1 " +
           "WHERE v.vmId = :vmId AND v.status = :expectedStatus")
    int applySyncedStatusIfCurrent(@Param("vmId") String vmId,
                                   @Param("expectedStatus") VmStatus expectedStatus,
                                   @Param("newStatus") VmStatus newStatus,
                                   @Param("active") Boolean active,
                                   @Param("drift") Boolean drift,
                                   @Param("syncedAt") Timestamp syncedAt);

    /** A state sync that found no drift: record the sync time and clear the drift flag. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.stateDriftDetected = false, v.lastStateSyncAt = :syncedAt, " +
           "v.version = v.version + 1 WHERE v.vmId = :vmId")
    int markSyncedWithoutDrift(@Param("vmId") String vmId, @Param("syncedAt") Timestamp syncedAt);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.metadata = :metadata, v.updatedAt = :at, v.version = v.version + 1 " +
           "WHERE v.vmId = :vmId")
    int updateMetadata(@Param("vmId") String vmId, @Param("metadata") String metadata, @Param("at") Timestamp at);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.tagsSyncedAt = :at, v.version = v.version + 1 WHERE v.vmId IN :vmIds")
    int markTagsSynced(@Param("vmIds") Collection<String> vmIds, @Param("at") Timestamp at);

    /**
     * Flag drift on one VM without writing the rest of the row, so discovery never overwrites
     * a concurrent change with stale values (M5). Inactive VMs are left alone.
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.stateDriftDetected = true, v.lastStateSyncAt = :syncedAt, v.updatedAt = :syncedAt, " +
           "v.version = v.version + 1 WHERE v.vmId = :vmId AND v.isActive = true")
    int markDriftIfActive(@Param("vmId") String vmId, @Param("syncedAt") Timestamp syncedAt);

    /**
     * No drift: clear the flag and record the sync time, only while the status is still the one
     * sync compared. updated_at is left alone: it dates the last status change, which the
     * transitional-state guard relies on (M5).
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.stateDriftDetected = false, v.lastStateSyncAt = :syncedAt, v.version = v.version + 1 " +
           "WHERE v.vmId = :vmId AND v.status = :expectedStatus")
    int markSyncedIfStatus(@Param("vmId") String vmId, @Param("expectedStatus") VmStatus expectedStatus,
                           @Param("syncedAt") Timestamp syncedAt);

    /** Rename from the cloud Name tag only if nobody renamed the VM since sync read it (M5). */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.name = :newName, v.displayName = :newDisplayName, v.version = v.version + 1 " +
           "WHERE v.vmId = :vmId AND v.name = :oldName")
    int updateNamesIfUnchanged(@Param("vmId") String vmId, @Param("oldName") String oldName,
                               @Param("newName") String newName, @Param("newDisplayName") String newDisplayName);

    /**
     * One more NOT_FOUND from state sync: count it and flag drift, while the VM is still active
     * and still in the status sync compared (M7).
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.notFoundCount = v.notFoundCount + 1, v.stateDriftDetected = true, " +
           "v.lastStateSyncAt = :syncedAt, v.version = v.version + 1 " +
           "WHERE v.vmId = :vmId AND v.isActive = true AND v.status = :expectedStatus")
    int incrementNotFound(@Param("vmId") String vmId, @Param("expectedStatus") VmStatus expectedStatus,
                          @Param("syncedAt") Timestamp syncedAt);

    @Query("SELECT v.notFoundCount FROM Vm v WHERE v.vmId = :vmId")
    Integer findNotFoundCount(@Param("vmId") String vmId);

    /** The instance was found again: the NOT_FOUND streak is over. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.notFoundCount = 0, v.version = v.version + 1 WHERE v.vmId = :vmId AND v.notFoundCount > 0")
    int resetNotFound(@Param("vmId") String vmId);

    /**
     * Reactivate an inactive VM with the status the cloud reports now; drift and the NOT_FOUND
     * streak are cleared. Only if it is still inactive (a second click changes nothing).
     */
    // clearAutomatically: in a web request the open session would otherwise return the stale row.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.isActive = true, v.status = :status, v.notFoundCount = 0, v.stateDriftDetected = false, " +
           "v.discoveryPending = :pending, v.discoveryIgnored = false, v.deletedAt = null, v.deletedBy = null, " +
           "v.lastStateSyncAt = :at, v.updatedAt = :at, v.version = v.version + 1 " +
           "WHERE v.vmId = :vmId AND v.isActive = false")
    int reactivateIfInactive(@Param("vmId") String vmId, @Param("status") VmStatus status,
                             @Param("pending") boolean pending, @Param("at") Timestamp at);

    /** A provider instance with its group and environment loaded (discovery runs without a session). */
    @Query("SELECT v FROM Vm v JOIN FETCH v.group g JOIN FETCH g.environment " +
           "WHERE v.provider = :provider AND v.providerVmId = :providerVmId")
    Optional<Vm> findByProviderVmIdFetchGroupAndEnvironment(
            @Param("provider") com.tcgdigital.vmcontrol.model.CloudProvider provider,
            @Param("providerVmId") String providerVmId);

    /**
     * EKS sync of a node group an operation is driving: refresh metadata and sync time only.
     * A whole-row save would bump updated_at (@UpdateTimestamp) and keep the transitional guard
     * "fresh" forever.
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("UPDATE Vm v SET v.metadata = :metadata, v.lastStateSyncAt = :syncedAt, v.version = v.version + 1 " +
           "WHERE v.vmId = :vmId")
    int refreshSyncMetadata(@Param("vmId") String vmId, @Param("metadata") String metadata,
                            @Param("syncedAt") Timestamp syncedAt);

    /**
     * Registry review lists (M34): DRIFT (active, drift flagged), PENDING (active, discovered and
     * not yet reviewed) or INACTIVE VMs of one environment, most recently changed first.
     */
    @Query(value = "SELECT v FROM Vm v JOIN FETCH v.group g WHERE g.environment.environmentId = :envId AND (" +
                   "(:state = 'DRIFT' AND v.isActive = true AND v.stateDriftDetected = true) OR " +
                   "(:state = 'PENDING' AND v.isActive = true AND v.discoveryPending = true) OR " +
                   "(:state = 'INACTIVE' AND v.isActive = false)) ORDER BY v.updatedAt DESC",
           countQuery = "SELECT COUNT(v) FROM Vm v WHERE v.group.environment.environmentId = :envId AND (" +
                   "(:state = 'DRIFT' AND v.isActive = true AND v.stateDriftDetected = true) OR " +
                   "(:state = 'PENDING' AND v.isActive = true AND v.discoveryPending = true) OR " +
                   "(:state = 'INACTIVE' AND v.isActive = false))")
    Page<Vm> findReviewPage(@Param("envId") String envId, @Param("state") String state, Pageable pageable);

    @Query("SELECT COUNT(v) FROM Vm v WHERE v.group.environment.environmentId = :envId " +
           "AND v.isActive = true AND v.stateDriftDetected = true")
    long countDriftInEnvironment(@Param("envId") String envId);

    @Query("SELECT COUNT(v) FROM Vm v WHERE v.group.environment.environmentId = :envId " +
           "AND v.isActive = true AND v.discoveryPending = true")
    long countPendingInEnvironment(@Param("envId") String envId);

    @Query("SELECT COUNT(v) FROM Vm v WHERE v.group.environment.environmentId = :envId AND v.isActive = false")
    long countInactiveInEnvironment(@Param("envId") String envId);

    /** Every VM row of a group, active and inactive (deleting the group would cascade them all, M2). */
    @Query("SELECT COUNT(v) FROM Vm v WHERE v.group.groupId = :groupId")
    long countAllByGroupId(@Param("groupId") String groupId);
}
