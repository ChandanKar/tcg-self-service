package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.StateSyncStatusDTO;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    private final Executor syncExecutor;

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
                            AuditService auditService,
                            @Qualifier("syncExecutor") Executor syncExecutor) {
        this.vmRepository = vmRepository;
        this.stateHistoryRepository = stateHistoryRepository;
        this.cloudProviderFactory = cloudProviderFactory;
        this.auditService = auditService;
        this.syncExecutor = syncExecutor;
    }

    /**
     * Sync all active VMs with their cloud provider status.
     * Active VMs are processed in parallel via syncExecutor.
     */
    public StateSyncStatusDTO syncAllVmStates() {
        if (!syncInProgress.compareAndSet(false, true)) {
            log.warn("State sync already in progress, skipping");
            return getSyncStatus();
        }

        log.info("Starting VM state sync for all active VMs");
        AtomicInteger vmCount = new AtomicInteger(0);
        AtomicInteger driftCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        try {
            List<Vm> activeVms = vmRepository.findByIsActiveTrue();
            vmCount.set(activeVms.size());

            // Process active VMs in parallel
            List<CompletableFuture<Void>> futures = activeVms.stream()
                    .map(vm -> CompletableFuture.runAsync(() -> {
                        try {
                            boolean hasDrift = syncVmState(vm);
                            if (hasDrift) driftCount.incrementAndGet();
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                            log.error("Failed to sync VM {}: {}", vm.getVmId(), e.getMessage());
                        }
                    }, syncExecutor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            lastSyncTime.set(Timestamp.from(Instant.now()));
            lastSyncStatus.set(errorCount.get() == 0 ? "success" : "partial");
            lastSyncVmCount.set(vmCount.get());
            lastDriftCount.set(driftCount.get());
            lastErrorCount.set(errorCount.get());

            log.info("VM state sync completed: {} VMs synced, {} drift detected, {} errors",
                    vmCount.get(), driftCount.get(), errorCount.get());

            auditService.logAction(null, AuditAction.SCHEDULED_JOB_EXECUTED, "job", "state_sync",
                    "VM State Sync", String.format("Synced %d VMs, %d drift, %d errors",
                            vmCount.get(), driftCount.get(), errorCount.get()));

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
    public boolean syncVmState(Vm vm) {
        VmStatus currentStatus = vm.getStatus();

        // Skip sync while an active operation is driving the VM through a transitional state
        if (currentStatus == VmStatus.STARTING || currentStatus == VmStatus.STOPPING) {
            log.debug("Skipping sync for VM {} — transitional state {}", vm.getVmId(), currentStatus);
            return false;
        }

        VmStatus cloudStatus = fetchCloudVmStatusWithRetry(vm, 2);

        if (cloudStatus == null || cloudStatus == VmStatus.UNKNOWN) {
            log.warn("Could not determine cloud status for VM {} (result: {}) — skipping to avoid false drift",
                    vm.getVmId(), cloudStatus);
            return false;
        }

        // Handle VM not found in cloud (deleted externally)
        if (cloudStatus == VmStatus.NOT_FOUND || cloudStatus == VmStatus.TERMINATED) {
            log.warn("VM {} ({}) is {} in cloud provider - marking as inactive",
                    vm.getName(), vm.getVmId(), cloudStatus);

            // Record state change
            String details = cloudStatus == VmStatus.NOT_FOUND
                    ? "VM not found in cloud provider - may have been deleted externally"
                    : "VM terminated in cloud provider";
            recordStateChange(vm, currentStatus, cloudStatus, "state_sync", null, null, details);

            // Update VM status and deactivate
            vm.setStatus(cloudStatus);
            vm.setIsActive(false);
            vm.setStateDriftDetected(true);
            vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
            vmRepository.save(vm);

            // Audit log with high severity
            auditService.logAction(null, AuditAction.STATE_DRIFT_DETECTED, "vm", vm.getVmId(),
                    vm.getName(), String.format("VM %s in cloud - marked inactive. Previous status: %s",
                            cloudStatus, currentStatus));

            return true;
        }

        // Sync VM name from cloud if name/displayName matches providerVmId (likely auto-generated)
        syncVmNameIfNeeded(vm);

        // Check for normal drift (status changed but VM still exists)
        if (currentStatus != cloudStatus) {
            log.info("State drift detected for VM {}: {} -> {}",
                    vm.getName(), currentStatus, cloudStatus);

            // Record state change
            recordStateChange(vm, currentStatus, cloudStatus, "state_sync", null, null,
                    "Drift detected during state sync");

            // Update VM status
            vm.setStatus(cloudStatus);
            vm.setStateDriftDetected(true);
            vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
            vmRepository.save(vm);

            // Audit log drift
            auditService.logAction(null, AuditAction.STATE_DRIFT_DETECTED, "vm", vm.getVmId(),
                    vm.getName(), String.format("State drift: %s -> %s", currentStatus, cloudStatus));

            return true;
        }

        // No drift — clear drift flag and update sync time
        vm.setStateDriftDetected(false);
        vm.setLastStateSyncAt(Timestamp.from(Instant.now()));
        vmRepository.save(vm);

        return false;
    }

    /**
     * Sync VM name from cloud provider if current name matches providerVmId.
     * This handles cases where VM was registered with instance ID as name.
     */
    private void syncVmNameIfNeeded(Vm vm) {
        String providerVmId = vm.getProviderVmId();
        String currentName = vm.getName();
        String currentDisplayName = vm.getDisplayName();

        // Check if name or displayName matches the providerVmId (instance ID)
        boolean nameNeedsSync = providerVmId != null && (
                providerVmId.equalsIgnoreCase(currentName) ||
                providerVmId.equalsIgnoreCase(currentDisplayName)
        );

        if (!nameNeedsSync) {
            return;
        }

        log.info("VM {} has name matching instance ID, fetching actual name from cloud", vm.getVmId());

        try {
            CloudProviderService providerService = cloudProviderFactory.getService(vm.getProvider());
            if (providerService == null || !providerService.isAvailable()) {
                log.warn("Cloud provider not available for VM name sync: {}", vm.getProvider());
                return;
            }

            String cloudVmName = providerService.getVmName(providerVmId, vm.getRegion());

            if (cloudVmName != null && !cloudVmName.isBlank()) {
                String oldName = vm.getName();
                String oldDisplayName = vm.getDisplayName();

                // Update name (lowercase, hyphenated) and displayName
                String newName = cloudVmName.toLowerCase().replaceAll("\\s+", "-");
                vm.setName(newName);
                vm.setDisplayName(cloudVmName);
                vmRepository.save(vm);

                log.info("Updated VM name from cloud: {} -> {} (display: {} -> {})",
                        oldName, newName, oldDisplayName, cloudVmName);

                // Audit log the name sync
                auditService.logAction(null, AuditAction.VM_NAME_SYNCED, "vm", vm.getVmId(),
                        cloudVmName, String.format("VM name synced from cloud. Old: %s/%s, New: %s/%s",
                                oldName, oldDisplayName, newName, cloudVmName));
            } else {
                log.debug("No name tag found in cloud for VM {}", vm.getVmId());
            }
        } catch (Exception e) {
            log.error("Error syncing VM name for {}: {}", vm.getVmId(), e.getMessage());
        }
    }

    /**
     * Retry wrapper around fetchCloudVmStatus — retries on null/UNKNOWN only.
     * Definitive states (NOT_FOUND, TERMINATED) are returned immediately.
     */
    private VmStatus fetchCloudVmStatusWithRetry(Vm vm, int maxAttempts) {
        VmStatus status = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            status = fetchCloudVmStatus(vm);
            if (status != null && status != VmStatus.UNKNOWN) {
                return status;
            }
            if (attempt < maxAttempts) {
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return status;
                }
                log.debug("Retrying cloud status fetch for VM {} (attempt {}/{})", vm.getVmId(), attempt + 1, maxAttempts);
            }
        }
        return status;
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

