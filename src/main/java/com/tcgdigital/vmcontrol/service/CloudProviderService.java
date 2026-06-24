package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.VmStatus;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for cloud provider VM operations.
 * Each cloud provider (AWS, Azure, GCP) implements this interface.
 */
public interface CloudProviderService {

    /**
     * Get the cloud provider this service handles.
     */
    CloudProvider getProvider();

    /**
     * Start a VM.
     * @param providerVmId The cloud provider's VM ID
     * @param region The region where the VM is located
     * @return CompletableFuture with the operation result
     */
    CompletableFuture<VmOperationResult> startVm(String providerVmId, String region);

    /**
     * Start a VM and report intermediate progress where the provider supports it.
     */
    default CompletableFuture<VmOperationResult> startVm(String providerVmId, String region,
                                                        OperationProgressListener progressListener) {
        return startVm(providerVmId, region);
    }

    /**
     * Stop a VM.
     * @param providerVmId The cloud provider's VM ID
     * @param region The region where the VM is located
     * @param force Whether to force stop (hard shutdown)
     * @return CompletableFuture with the operation result
     */
    CompletableFuture<VmOperationResult> stopVm(String providerVmId, String region, boolean force);

    /**
     * Stop a VM and report intermediate progress where the provider supports it.
     */
    default CompletableFuture<VmOperationResult> stopVm(String providerVmId, String region, boolean force,
                                                       OperationProgressListener progressListener) {
        return stopVm(providerVmId, region, force);
    }

    /**
     * Get current VM status from the cloud provider.
     * @param providerVmId The cloud provider's VM ID
     * @param region The region where the VM is located
     * @return Current VM status
     */
    VmStatus getVmStatus(String providerVmId, String region);

    /**
     * Get the VM name from the cloud provider (e.g., EC2 Name tag).
     * @param providerVmId The cloud provider's VM ID
     * @param region The region where the VM is located
     * @return VM name from cloud provider, or null if not found
     */
    String getVmName(String providerVmId, String region);

    /**
     * Check if the service is available/configured.
     */
    boolean isAvailable();

    /**
     * Fetch the status of multiple VMs in a single cloud API call where supported.
     * The default implementation falls back to individual {@link #getVmStatus} calls.
     * AWS overrides this with a batched DescribeInstances request (O(1) per region).
     *
     * @param providerVmIds cloud provider instance IDs to query
     * @param region        region to query
     * @return map of providerVmId → VmStatus; missing entries mean UNKNOWN
     */
    default java.util.Map<String, VmStatus> getVmStatusBatch(
            java.util.List<String> providerVmIds, String region) {
        java.util.Map<String, VmStatus> result = new java.util.HashMap<>();
        for (String id : providerVmIds) {
            result.put(id, getVmStatus(id, region));
        }
        return result;
    }

    /**
     * List all instance IDs currently running in the given regions.
     * Used by the VM discovery job to detect untracked instances.
     * @param regions list of region strings to scan
     * @return list of provider VM IDs found in the cloud
     */
    default java.util.List<String> discoverInstanceIds(java.util.List<String> regions) {
        return java.util.Collections.emptyList();
    }

    /**
     * Receives provider-specific operation progress for a single VM.
     */
    @FunctionalInterface
    interface OperationProgressListener {
        void onProgress(VmOperationProgress progress);
    }

    /**
     * Progress snapshot for a VM operation.
     */
    class VmOperationProgress {
        private final VmStatus status;
        private final String stageLabel;
        private final int progressPercentage;
        private final Integer statusChecksPassed;
        private final Integer statusChecksTotal;

        public VmOperationProgress(VmStatus status, String stageLabel, int progressPercentage,
                                   Integer statusChecksPassed, Integer statusChecksTotal) {
            this.status = status;
            this.stageLabel = stageLabel;
            this.progressPercentage = progressPercentage;
            this.statusChecksPassed = statusChecksPassed;
            this.statusChecksTotal = statusChecksTotal;
        }

        public static VmOperationProgress of(VmStatus status, String stageLabel, int progressPercentage) {
            return new VmOperationProgress(status, stageLabel, progressPercentage, null, null);
        }

        public static VmOperationProgress checks(VmStatus status, int progressPercentage,
                                                 int statusChecksPassed, int statusChecksTotal) {
            return new VmOperationProgress(
                    status,
                    "AWS checks " + statusChecksPassed + "/" + statusChecksTotal + " passed",
                    progressPercentage,
                    statusChecksPassed,
                    statusChecksTotal
            );
        }

        public VmStatus getStatus() {
            return status;
        }

        public String getStageLabel() {
            return stageLabel;
        }

        public int getProgressPercentage() {
            return progressPercentage;
        }

        public Integer getStatusChecksPassed() {
            return statusChecksPassed;
        }

        public Integer getStatusChecksTotal() {
            return statusChecksTotal;
        }
    }

    /**
     * Result of a VM operation.
     */
    class VmOperationResult {
        private final boolean success;
        private final String message;
        private final String requestId;
        private final VmStatus resultStatus;

        public VmOperationResult(boolean success, String message, String requestId, VmStatus resultStatus) {
            this.success = success;
            this.message = message;
            this.requestId = requestId;
            this.resultStatus = resultStatus;
        }

        public static VmOperationResult success(String requestId, VmStatus status) {
            return new VmOperationResult(true, "Operation successful", requestId, status);
        }

        public static VmOperationResult failure(String message) {
            return new VmOperationResult(false, message, null, null);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getRequestId() {
            return requestId;
        }

        public VmStatus getResultStatus() {
            return resultStatus;
        }
    }
}

