package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.StateSyncStatusDTO;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for VM state synchronization and drift detection.
 */
@Service
public class StateSyncService {

    private static final Logger log = LoggerFactory.getLogger(StateSyncService.class);

    private final VmRepository vmRepository;
    private final VmStateHistoryRepository stateHistoryRepository;
    private final CloudProviderFactory cloudProviderFactory;
    private final AuditService auditService;

    @Value("${vm.state.sync.interval:300000}")
    private long syncIntervalMs;

    // Sync status tracking
    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);
    private final AtomicReference<Timestamp> lastSyncTime = new AtomicReference<>();
    private final AtomicReference<String> lastSyncStatus = new AtomicReference<>("never");
    private final AtomicInteger lastSyncVmCount = new AtomicInteger(0);
    private final AtomicInteger lastDriftCount = new AtomicInteger(0);
    private final AtomicInteger lastErrorCount = new AtomicInteger(0);

    public StateSyncService(VmRepository vmRepository,
                            VmStateHistoryRepository stateHistoryRepository,
                            CloudProviderFactory cloudProviderFactory,
                            AuditService auditService) {
        this.vmRepository = vmRepository;
        this.stateHistoryRepository = stateHistoryRepository;
        this.cloudProviderFactory = cloudProviderFactory;
        this.auditService = auditService;
    }

    /**
     * Sync all active VMs with their cloud provider status.
     */
    @Transactional
    public StateSyncStatusDTO syncAllVmStates() {
        if (!syncInProgress.compareAndSet(false, true)) {
            log.warn("State sync already in progress, skipping");
            return getSyncStatus();
        }

        log.info("Starting VM state sync for all active VMs");
        int vmCount = 0;
        int driftCount = 0;
        int errorCount = 0;

        try {
            List<Vm> activeVms = vmRepository.findByIsActiveTrue();
            vmCount = activeVms.size();

            for (Vm vm : activeVms) {
                try {
                    boolean hasDrift = syncVmState(vm);
                    if (hasDrift) {
                        driftCount++;
                    }
                } catch (Exception e) {
                    errorCount++;
                    log.error("Failed to sync VM {}: {}", vm.getVmId(), e.getMessage());
                }
            }

            lastSyncTime.set(Timestamp.from(Instant.now()));
            lastSyncStatus.set(errorCount == 0 ? "success" : "partial");
            lastSyncVmCount.set(vmCount);
            lastDriftCount.set(driftCount);
            lastErrorCount.set(errorCount);

            log.info("VM state sync completed: {} VMs synced, {} drift detected, {} errors",
                    vmCount, driftCount, errorCount);

            // Audit log
            auditService.logAction(null, AuditAction.SCHEDULED_JOB_EXECUTED, "job", "state_sync",
                    "VM State Sync", String.format("Synced %d VMs, %d drift, %d errors", vmCount, driftCount, errorCount));

        } finally {
            syncInProgress.set(false);
        }

        return getSyncStatus();
    }

    /**
     * Sync VMs for a specific environment.
     */
    @Transactional
    public int syncEnvironmentVmStates(String environmentId) {
        log.info("Starting VM state sync for environment: {}", environmentId);
        int driftCount = 0;

        List<Vm> vms = vmRepository.findByEnvironmentId(environmentId);
        for (Vm vm : vms) {
            try {
                if (syncVmState(vm)) {
                    driftCount++;
                }
            } catch (Exception e) {
                log.error("Failed to sync VM {}: {}", vm.getVmId(), e.getMessage());
            }
        }

        return driftCount;
    }

    /**
     * Sync a single VM's state with cloud provider.
     * Returns true if drift was detected.
     */
    @Transactional
    public boolean syncVmState(Vm vm) {
        VmStatus currentStatus = vm.getStatus();
        VmStatus cloudStatus = fetchCloudVmStatus(vm);

        if (cloudStatus == null) {
            log.warn("Could not fetch cloud status for VM: {}", vm.getVmId());
            return false;
        }

        // Check for drift
        if (currentStatus != cloudStatus) {
            log.info("State drift detected for VM {}: {} -> {}",
                    vm.getName(), currentStatus, cloudStatus);

            // Record state change
            recordStateChange(vm, currentStatus, cloudStatus, "state_sync", null, null,
                    "Drift detected during state sync");

            // Update VM status
            vm.setStatus(cloudStatus);
            vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
            vmRepository.save(vm);

            // Audit log drift
            auditService.logAction(null, AuditAction.STATE_DRIFT_DETECTED, "vm", vm.getVmId(),
                    vm.getName(), String.format("State drift: %s -> %s", currentStatus, cloudStatus));

            return true;
        }

        // Update last sync time even if no drift
        vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
        vmRepository.save(vm);

        return false;
    }

    /**
     * Fetch VM status from cloud provider.
     */
    private VmStatus fetchCloudVmStatus(Vm vm) {
        try {
            CloudProviderService providerService = cloudProviderFactory.getService(vm.getProvider());
            if (providerService == null || !providerService.isAvailable()) {
                log.warn("No cloud provider available for: {}", vm.getProvider());
                return null;
            }

            String providerVmId = vm.getProviderVmId();
            if (providerVmId == null || providerVmId.isBlank()) {
                log.warn("VM {} has no provider VM ID", vm.getVmId());
                return null;
            }

            return providerService.getVmStatus(providerVmId, vm.getRegion());

        } catch (Exception e) {
            log.error("Error fetching cloud status for VM {}: {}", vm.getVmId(), e.getMessage());
            return null;
        }
    }

    /**
     * Record a state change in history.
     */
    @Transactional
    public VmStateHistory recordStateChange(Vm vm, VmStatus previousStatus, VmStatus newStatus,
                                            String changeSource, String userId, String operationId,
                                            String details) {
        VmStateHistory history = VmStateHistory.builder()
                .historyId(UUID.randomUUID().toString())
                .vm(vm)
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .changeSource(changeSource)
                .changedByUserId(userId)
                .operationId(operationId)
                .details(details)
                .build();

        return stateHistoryRepository.save(history);
    }

    /**
     * Get state history for a VM.
     */
    public List<VmStateHistory> getVmStateHistory(String vmId) {
        return stateHistoryRepository.findByVmVmIdOrderByChangedAtDesc(vmId);
    }

    /**
     * Get state history for a VM with pagination.
     */
    public Page<VmStateHistory> getVmStateHistory(String vmId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return stateHistoryRepository.findByVmVmIdOrderByChangedAtDesc(vmId, pageable);
    }

    /**
     * Get recent state changes across all VMs.
     */
    public List<VmStateHistory> getRecentStateChanges() {
        return stateHistoryRepository.findTop50ByOrderByChangedAtDesc();
    }

    /**
     * Get drift events (unexpected state changes).
     */
    public Page<VmStateHistory> getDriftEvents(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return stateHistoryRepository.findDriftEvents(pageable);
    }

    /**
     * Get drift events in a date range.
     */
    public List<VmStateHistory> getDriftEventsInRange(LocalDate startDate, LocalDate endDate) {
        Timestamp start = Timestamp.valueOf(startDate.atStartOfDay());
        Timestamp end = Timestamp.valueOf(endDate.plusDays(1).atStartOfDay());
        return stateHistoryRepository.findDriftEventsInRange(start, end);
    }

    /**
     * Count drift events in a date range.
     */
    public long countDriftEventsInRange(LocalDate startDate, LocalDate endDate) {
        Timestamp start = Timestamp.valueOf(startDate.atStartOfDay());
        Timestamp end = Timestamp.valueOf(endDate.plusDays(1).atStartOfDay());
        return stateHistoryRepository.countDriftEventsInRange(start, end);
    }

    /**
     * Get current sync status.
     */
    public StateSyncStatusDTO getSyncStatus() {
        StateSyncStatusDTO status = new StateSyncStatusDTO();
        status.setLastSyncTime(lastSyncTime.get());
        status.setLastSyncStatus(lastSyncStatus.get());
        status.setTotalVmsSynced(lastSyncVmCount.get());
        status.setDriftDetected(lastDriftCount.get());
        status.setSyncErrors(lastErrorCount.get());
        status.setSyncInProgress(syncInProgress.get());

        // Calculate next sync time
        Timestamp lastSync = lastSyncTime.get();
        if (lastSync != null) {
            long elapsedMs = System.currentTimeMillis() - lastSync.getTime();
            long remainingMs = Math.max(0, syncIntervalMs - elapsedMs);
            status.setNextSyncInSeconds(remainingMs / 1000);
        } else {
            status.setNextSyncInSeconds(0);
        }

        return status;
    }

    /**
     * Check if sync is currently in progress.
     */
    public boolean isSyncInProgress() {
        return syncInProgress.get();
    }
}

