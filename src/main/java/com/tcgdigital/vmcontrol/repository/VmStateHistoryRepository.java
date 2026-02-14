package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmStateHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

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
}

