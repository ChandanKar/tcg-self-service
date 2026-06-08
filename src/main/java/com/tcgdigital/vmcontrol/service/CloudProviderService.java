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
     * Stop a VM.
     * @param providerVmId The cloud provider's VM ID
     * @param region The region where the VM is located
     * @param force Whether to force stop (hard shutdown)
     * @return CompletableFuture with the operation result
     */
    CompletableFuture<VmOperationResult> stopVm(String providerVmId, String region, boolean force);

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
     * List all instance IDs currently running in the given regions.
     * Used by the VM discovery job to detect untracked instances.
     * @param regions list of region strings to scan
     * @return list of provider VM IDs found in the cloud
     */
    default java.util.List<String> discoverInstanceIds(java.util.List<String> regions) {
        return java.util.Collections.emptyList();
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

