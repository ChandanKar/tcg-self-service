package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmStateHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Repository for VmStateHistory entity.
 */
@Repository
public interface VmStateHistoryRepository extends JpaRepository<VmStateHistory, String> {

    /**
     * Find state history for a VM.
     */
    List<VmStateHistory> findByVmVmIdOrderByChangedAtDesc(String vmId);

    /**
     * Find state history for a VM with pagination.
     */
    Page<VmStateHistory> findByVmVmIdOrderByChangedAtDesc(String vmId, Pageable pageable);

    /**
     * Find recent state changes across all VMs.
     */
    List<VmStateHistory> findTop50ByOrderByChangedAtDesc();

    /**
     * Find recent state changes with pagination.
     */
    Page<VmStateHistory> findAllByOrderByChangedAtDesc(Pageable pageable);

    /**
     * Find drift events (state_sync source indicates external change).
     */
    @Query("SELECT h FROM VmStateHistory h WHERE h.changeSource = 'state_sync' AND h.changedAt BETWEEN :startTime AND :endTime ORDER BY h.changedAt DESC")
    List<VmStateHistory> findDriftEventsInRange(Timestamp startTime, Timestamp endTime);

    /**
     * Find drift events with pagination.
     */
    @Query("SELECT h FROM VmStateHistory h WHERE h.changeSource = 'state_sync' ORDER BY h.changedAt DESC")
    Page<VmStateHistory> findDriftEvents(Pageable pageable);

    /**
     * Find state changes for an environment.
     */
    @Query("SELECT h FROM VmStateHistory h WHERE h.vm.group.environment.environmentId = :environmentId ORDER BY h.changedAt DESC")
    Page<VmStateHistory> findByEnvironmentId(String environmentId, Pageable pageable);

    /**
     * Count drift events in a time range.
     */
    @Query("SELECT COUNT(h) FROM VmStateHistory h WHERE h.changeSource = 'state_sync' AND h.changedAt BETWEEN :startTime AND :endTime")
    long countDriftEventsInRange(Timestamp startTime, Timestamp endTime);

    /**
     * Find last state change for a VM.
     */
    @Query("SELECT h FROM VmStateHistory h WHERE h.vm.vmId = :vmId ORDER BY h.changedAt DESC")
    List<VmStateHistory> findLastStateChangeByVmId(String vmId, Pageable pageable);

    /**
     * Transitions for a batch of VMs within a time window, in one query — used to derive
     * runtime hours for a whole page of VMs at once rather than per-VM (avoids N+1).
     */
    List<VmStateHistory> findByVmVmIdInAndChangedAtBetweenOrderByVmVmIdAscChangedAtAsc(
            List<String> vmIds, Timestamp start, Timestamp end);

    /**
     * Fallback for a single VM whose seed state (the state it was in immediately before a
     * window) wasn't found in the batched lookback query above.
     */
    Optional<VmStateHistory> findTopByVmVmIdAndChangedAtLessThanOrderByChangedAtDesc(String vmId, Timestamp before);

    /** State changes of the given VMs after a moment, oldest first (idle-stop savings, E16-T04). */
    @Query("SELECT h FROM VmStateHistory h WHERE h.vm.vmId IN :vmIds AND h.changedAt > :after ORDER BY h.changedAt ASC")
    List<VmStateHistory> findByVmIdsChangedAfter(@org.springframework.data.repository.query.Param("vmIds") List<String> vmIds,
                                                 @org.springframework.data.repository.query.Param("after") Timestamp after);
}
