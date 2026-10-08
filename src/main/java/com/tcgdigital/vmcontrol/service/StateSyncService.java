package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.service.support.NameSanitizer;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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
    private final NotificationService notificationService;
    private final Executor syncExecutor;

    @Value("${vm.state.sync.interval:300000}")
    private long syncIntervalMs;

    /**
     * A VM in STARTING/STOPPING is assumed to be driven by an in-flight operation and is
     * skipped from sync — but only while that assumption is plausible. The longest legitimate
     * single-VM operation timeout in this app is 15 minutes (EKS node group scaling); this
     * threshold is set comfortably above that so sync never interrupts a real operation, while
     * still eventually reconciling a VM whose status update was orphaned by a crashed/failed
     * operation that never wrote back a terminal status.
     */
    @Value("${vm.state.sync.stale-transitional-minutes:20}")
    private long staleTransitionalMinutes;

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
                            NotificationService notificationService,
                            @Qualifier("syncExecutor") Executor syncExecutor) {
        this.vmRepository = vmRepository;
        this.stateHistoryRepository = stateHistoryRepository;
        this.cloudProviderFactory = cloudProviderFactory;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.syncExecutor = syncExecutor;
    }

    /**
     * Sync all active VMs with their cloud provider status.
     *
     * Instead of one API call per VM (O(n)), VMs are grouped by (provider, region) and
     * a single batch DescribeInstances call is issued per group.  For 161 VMs all in
     * ap-south-1 this reduces 161 AWS API calls to 1.  If a batch call fails, affected
     * VMs fall back to the individual-fetch path automatically.
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
            // Group and environment are fetched with the VMs and copied into SyncTarget
            // snapshots: worker threads have no session and must never touch the entities (H23).
            List<SyncTarget> activeVms = vmRepository.findActiveForSync().stream().map(SyncTarget::of).toList();
            vmCount.set(activeVms.size());

            // VMs in transitional states are being driven by an in-flight operation — skip them,
            // unless they've been transitional for longer than any real operation could take
            // (see staleTransitionalMinutes), in which case something orphaned the status and
            // sync should reconcile it rather than leave it stuck forever.
            List<SyncTarget> vmsToSync = activeVms.stream()
                    .filter(vm -> !isFreshTransitional(vm))
                    .toList();

            int skipped = activeVms.size() - vmsToSync.size();
            if (skipped > 0) {
                log.debug("Skipping {} VM(s) in transitional state (STARTING/STOPPING) less than {}m old",
                        skipped, staleTransitionalMinutes);
            }

            // Group by "PROVIDER:region" — one batch API call per group
            Map<String, List<SyncTarget>> groups = vmsToSync.stream()
                    .filter(vm -> vm.providerVmId() != null && !vm.providerVmId().isBlank())
                    .collect(Collectors.groupingBy(
                            vm -> vm.provider().name() + ":" + vm.region()));

            // Fetch all statuses in parallel — one call per (provider, region) group
            Map<String, Map<String, VmStatus>> batchResults = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> fetchFutures = groups.entrySet().stream()
                    .map(entry -> CompletableFuture.runAsync(() -> {
                        String[] parts = entry.getKey().split(":", 2);
                        CloudProvider provider = CloudProvider.valueOf(parts[0]);
                        String region = parts[1];
                        List<String> ids = entry.getValue().stream()
                                .map(SyncTarget::providerVmId).toList();
                        try {
                            CloudProviderService svc = cloudProviderFactory.getService(provider);
                            if (!svc.isAvailable()) {
                                log.warn("Cloud provider {} not available — {} VM(s) will be skipped",
                                        provider, ids.size());
                                return;
                            }
                            Map<String, VmStatus> statuses = svc.getVmStatusBatch(ids, region);
                            if (!statuses.isEmpty()) {
                                batchResults.put(entry.getKey(), statuses);
                            } else {
                                log.warn("Batch fetch returned no results for {}/{} — VMs will fall back to individual fetch",
                                        provider, region);
                            }
                        } catch (Exception e) {
                            log.error("Batch fetch failed for {}/{}: {}", provider, region, e.getMessage());
                            errorCount.addAndGet(ids.size());
                        }
                    }, syncExecutor))
                    .toList();

            CompletableFuture.allOf(fetchFutures.toArray(new CompletableFuture[0])).join();
            log.info("Batch status fetch complete: {} group(s) queried", groups.size());

            // Apply the pre-fetched statuses to each VM in parallel
            List<CompletableFuture<Void>> applyFutures = vmsToSync.stream()
                    .map(vm -> CompletableFuture.runAsync(() -> {
                        try {
                            if (vm.providerVmId() == null || vm.providerVmId().isBlank()) {
                                log.warn("VM {} has no provider ID — skipping", vm.vmId());
                                return;
                            }
                            String key = vm.provider().name() + ":" + vm.region();
                            Map<String, VmStatus> regionStatuses = batchResults.get(key);

                            VmStatus cloudStatus;
                            if (regionStatuses != null) {
                                cloudStatus = regionStatuses.getOrDefault(
                                        vm.providerVmId(), VmStatus.UNKNOWN);
                            } else {
                                // Batch failed for this group — individual fallback
                                cloudStatus = fetchCloudVmStatusWithRetry(vm, 2);
                            }

                            if (applySyncedStatus(vm, cloudStatus)) driftCount.incrementAndGet();
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                            log.error("Failed to apply sync for VM {}: {}", vm.vmId(), e.getMessage());
                        }
                    }, syncExecutor))
                    .toList();

            CompletableFuture.allOf(applyFutures.toArray(new CompletableFuture[0])).join();

            lastSyncTime.set(Timestamp.from(Instant.now()));
            lastSyncStatus.set(errorCount.get() == 0 ? "success" : "partial");
            lastSyncVmCount.set(vmCount.get());
            lastDriftCount.set(driftCount.get());
            lastErrorCount.set(errorCount.get());

            log.info("VM state sync completed: {}/{} VMs synced across {} group(s), {} drift, {} errors",
                    vmsToSync.size(), vmCount.get(), groups.size(), driftCount.get(), errorCount.get());

            auditService.logAction(null, AuditAction.SCHEDULED_JOB_EXECUTED, "job", "state_sync",
                    "VM State Sync", String.format("Synced %d VMs in %d group(s), %d drift, %d errors",
                            vmsToSync.size(), groups.size(), driftCount.get(), errorCount.get()));

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
     * Used by {@link #syncEnvironmentVmStates} and manual single-VM triggers.
     * The full-sync path uses {@link #applySyncedStatus} directly with batch-fetched statuses.
     * The VM is re-read with its group and environment, so a detached entity works too.
     */
    public boolean syncVmState(Vm vm) {
        SyncTarget target = vmRepository.findByIdFetchGroupAndEnvironment(vm.getVmId())
                .map(SyncTarget::of).orElse(null);
        if (target == null) {
            log.debug("VM {} no longer exists; nothing to sync", vm.getVmId());
            return false;
        }
        if (isFreshTransitional(target)) {
            log.debug("Skipping sync for VM {} — transitional state {} less than {}m old",
                    target.vmId(), target.status(), staleTransitionalMinutes);
            return false;
        }
        VmStatus cloudStatus = fetchCloudVmStatusWithRetry(target, 2);
        return applySyncedStatus(target, cloudStatus);
    }

    /**
     * What sync needs of a VM, copied while the entity is attached. Worker threads only see
     * this snapshot; the status in it is the one every conditional update compares against.
     */
    record SyncTarget(String vmId, String name, String displayName, CloudProvider provider, String region,
                      String providerVmId, VmStatus status, Boolean active, Timestamp updatedAt,
                      String envId, String envName) {
        static SyncTarget of(Vm vm) {
            Environment env = vm.getGroup().getEnvironment();
            return new SyncTarget(vm.getVmId(), vm.getName(), vm.getDisplayName(), vm.getProvider(), vm.getRegion(),
                    vm.getProviderVmId(), vm.getStatus(), vm.getIsActive(), vm.getUpdatedAt(),
                    env.getEnvironmentId(), env.getName());
        }
    }

    /**
     * True if the VM is in STARTING/STOPPING and recently entered that status (within
     * {@link #staleTransitionalMinutes}), meaning it's plausibly still driven by a real
     * in-flight operation and should be left alone by sync.
     */
    private boolean isFreshTransitional(SyncTarget vm) {
        VmStatus status = vm.status();
        if (status != VmStatus.STARTING && status != VmStatus.STOPPING) {
            return false;
        }
        Timestamp updatedAt = vm.updatedAt();
        if (updatedAt == null) {
            return false;
        }
        long minutesInState = Duration.between(updatedAt.toInstant(), Instant.now()).toMinutes();
        return minutesInState < staleTransitionalMinutes;
    }

    /**
     * Apply a pre-fetched cloud status to the VM, recording drift and updating the DB.
     * Every write is conditional on the status sync read: an operation that changed the VM
     * meanwhile wins, and no false drift is recorded (H14, M5).
     */
    private boolean applySyncedStatus(SyncTarget vm, VmStatus cloudStatus) {
        VmStatus currentStatus = vm.status();

        if (cloudStatus == null || cloudStatus == VmStatus.UNKNOWN) {
            log.warn("Could not determine cloud status for VM {} (result: {}) — skipping to avoid false drift",
                    vm.vmId(), cloudStatus);
            return false;
        }

        if (cloudStatus == VmStatus.NOT_FOUND || cloudStatus == VmStatus.TERMINATED) {
            log.warn("VM {} ({}) is {} in cloud — marking as inactive",
                    vm.name(), vm.vmId(), cloudStatus);

            String details = cloudStatus == VmStatus.NOT_FOUND
                    ? "VM not found in cloud provider - may have been deleted externally"
                    : "VM terminated in cloud provider";
            if (vmRepository.applySyncedStatusIfCurrent(vm.vmId(), currentStatus, cloudStatus,
                    false, true, Timestamp.from(Instant.now())) == 0) {
                log.info("VM {} changed while syncing; not marking it {}", vm.name(), cloudStatus);
                return false;
            }
            recordDrift(vm, currentStatus, cloudStatus, details,
                    String.format("VM %s in cloud - marked inactive. Previous status: %s", cloudStatus, currentStatus));
            return true;
        }

        // Sync name from cloud if the stored name is still the raw instance ID
        syncVmNameIfNeeded(vm);

        if (currentStatus != cloudStatus) {
            if (vmRepository.applySyncedStatusIfCurrent(vm.vmId(), currentStatus, cloudStatus,
                    vm.active(), true, Timestamp.from(Instant.now())) == 0) {
                log.debug("VM {} status changed concurrently; skipping drift {} -> {}",
                        vm.name(), currentStatus, cloudStatus);
                return false;
            }
            log.info("State drift detected for VM {}: {} -> {}", vm.name(), currentStatus, cloudStatus);
            recordDrift(vm, currentStatus, cloudStatus, "Drift detected during state sync",
                    String.format("State drift: %s -> %s", currentStatus, cloudStatus));
            return true;
        }

        // No drift — clear the flag and record the sync time (not updated_at)
        vmRepository.markSyncedIfStatus(vm.vmId(), currentStatus, Timestamp.from(Instant.now()));
        return false;
    }

    /** History, audit and notification for a drift that was applied. */
    private void recordDrift(SyncTarget vm, VmStatus from, VmStatus to, String historyDetails, String auditDetails) {
        recordStateChange(vmRepository.getReferenceById(vm.vmId()), from, to, "state_sync", null, null, historyDetails);
        auditService.logEnvironmentAction(null, AuditAction.STATE_DRIFT_DETECTED, vm.envId(), vm.envName(),
                "vm", vm.vmId(), vm.name(), auditDetails);
        runNotificationSideEffect("notify state drift", vm.vmId(), () ->
                notificationService.notifyStateDriftDetected(vm.envId(), vm.envName(), vm.name(),
                        from.name(), to.name(), vm.vmId()));
    }

    /**
     * Sync VM name from cloud provider if current name matches providerVmId.
     * This handles cases where VM was registered with instance ID as name.
     */
    private void syncVmNameIfNeeded(SyncTarget vm) {
        String providerVmId = vm.providerVmId();
        String currentName = vm.name();
        String currentDisplayName = vm.displayName();

        // Check if name or displayName matches the providerVmId (instance ID)
        boolean nameNeedsSync = providerVmId != null && (
                providerVmId.equalsIgnoreCase(currentName) ||
                providerVmId.equalsIgnoreCase(currentDisplayName)
        );

        if (!nameNeedsSync) {
            return;
        }

        log.info("VM {} has name matching instance ID, fetching actual name from cloud", vm.vmId());

        try {
            CloudProviderService providerService = cloudProviderFactory.getService(vm.provider());
            if (providerService == null || !providerService.isAvailable()) {
                log.warn("Cloud provider not available for VM name sync: {}", vm.provider());
                return;
            }

            // Name tags are free text in AWS; strip markup characters before storing (C3).
            String cloudVmName = NameSanitizer.clean(providerService.getVmName(providerVmId, vm.region()));

            if (cloudVmName != null && !cloudVmName.isBlank()) {
                // Update name (lowercase, hyphenated) and displayName
                String newName = cloudVmName.toLowerCase().replaceAll("\\s+", "-");
                // Only the two name columns, and only if nobody renamed the VM meanwhile (M5).
                if (vmRepository.updateNamesIfUnchanged(vm.vmId(), currentName, newName, cloudVmName) == 0) {
                    log.info("VM {} was renamed while syncing; keeping the new name", vm.vmId());
                    return;
                }

                log.info("Updated VM name from cloud: {} -> {} (display: {} -> {})",
                        currentName, newName, currentDisplayName, cloudVmName);

                // Audit log the name sync
                auditService.logEnvironmentAction(null, AuditAction.VM_NAME_SYNCED, vm.envId(), vm.envName(),
                        "vm", vm.vmId(), cloudVmName,
                        String.format("VM name synced from cloud. Old: %s/%s, New: %s/%s",
                                currentName, currentDisplayName, newName, cloudVmName));
            } else {
                log.debug("No name tag found in cloud for VM {}", vm.vmId());
            }
        } catch (Exception e) {
            log.error("Error syncing VM name for {}: {}", vm.vmId(), e.getMessage());
        }
    }

    /**
     * Retry wrapper around fetchCloudVmStatus — retries on null/UNKNOWN only.
     * Definitive states (NOT_FOUND, TERMINATED) are returned immediately.
     */
    private VmStatus fetchCloudVmStatusWithRetry(SyncTarget vm, int maxAttempts) {
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
                log.debug("Retrying cloud status fetch for VM {} (attempt {}/{})", vm.vmId(), attempt + 1, maxAttempts);
            }
        }
        return status;
    }

    /**
     * Fetch VM status from cloud provider.
     */
    private VmStatus fetchCloudVmStatus(SyncTarget vm) {
        try {
            CloudProviderService providerService = cloudProviderFactory.getService(vm.provider());
            if (providerService == null || !providerService.isAvailable()) {
                log.warn("No cloud provider available for: {}", vm.provider());
                return null;
            }

            String providerVmId = vm.providerVmId();
            if (providerVmId == null || providerVmId.isBlank()) {
                log.warn("VM {} has no provider VM ID", vm.vmId());
                return null;
            }

            return providerService.getVmStatus(providerVmId, vm.region());

        } catch (Exception e) {
            log.error("Error fetching cloud status for VM {}: {}", vm.vmId(), e.getMessage());
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

    private void runNotificationSideEffect(String action, String entityId, Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            log.warn("Could not {} for {}: {}", action, entityId, e.getMessage());
        }
    }
}

