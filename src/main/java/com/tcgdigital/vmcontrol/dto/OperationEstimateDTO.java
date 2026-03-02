package com.tcgdigital.vmcontrol.dto;

import java.util.List;

/**
 * DTO for operation time estimates based on historical data.
 * Returned before an operation starts so the UI can show the user
 * an expected duration.
 */
public class OperationEstimateDTO {

    /** Operation type this estimate applies to (START / STOP). */
    private String operationType;

    /** Scope level: ENVIRONMENT, GROUP, or VM */
    private String scopeLevel;

    /** ID of the scoped entity (environmentId, groupId, or vmId) */
    private String scopeId;

    /** Display name of the scoped entity */
    private String scopeName;

    /** Number of completed past executions used to compute the average. */
    private int sampleCount;

    /** Average total wall-clock seconds for the environment to complete. */
    private Double avgEnvironmentSeconds;

    /** Min / max wall-clock seconds observed across past executions. */
    private Double minEnvironmentSeconds;
    private Double maxEnvironmentSeconds;

    /** Per-VM averages, ordered by sequence position. */
    private List<VmEstimate> vmEstimates;

    public OperationEstimateDTO() {}

    // ------------------------------------------------------------------ //
    //  Nested: per-VM estimate                                            //
    // ------------------------------------------------------------------ //

    public static class VmEstimate {
        private String vmId;
        private String vmName;
        private int sequencePosition;
        private int sampleCount;
        private Double avgSeconds;

        public VmEstimate() {}

        public String getVmId() { return vmId; }
        public void setVmId(String vmId) { this.vmId = vmId; }

        public String getVmName() { return vmName; }
        public void setVmName(String vmName) { this.vmName = vmName; }

        public int getSequencePosition() { return sequencePosition; }
        public void setSequencePosition(int sequencePosition) { this.sequencePosition = sequencePosition; }

        public int getSampleCount() { return sampleCount; }
        public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }

        public Double getAvgSeconds() { return avgSeconds; }
        public void setAvgSeconds(Double avgSeconds) { this.avgSeconds = avgSeconds; }
    }

    // ------------------------------------------------------------------ //
    //  Getters / Setters                                                  //
    // ------------------------------------------------------------------ //

    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }

    public String getScopeLevel() { return scopeLevel; }
    public void setScopeLevel(String scopeLevel) { this.scopeLevel = scopeLevel; }

    public String getScopeId() { return scopeId; }
    public void setScopeId(String scopeId) { this.scopeId = scopeId; }

    public String getScopeName() { return scopeName; }
    public void setScopeName(String scopeName) { this.scopeName = scopeName; }

    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }

    public Double getAvgEnvironmentSeconds() { return avgEnvironmentSeconds; }
    public void setAvgEnvironmentSeconds(Double avgEnvironmentSeconds) { this.avgEnvironmentSeconds = avgEnvironmentSeconds; }

    public Double getMinEnvironmentSeconds() { return minEnvironmentSeconds; }
    public void setMinEnvironmentSeconds(Double minEnvironmentSeconds) { this.minEnvironmentSeconds = minEnvironmentSeconds; }

    public Double getMaxEnvironmentSeconds() { return maxEnvironmentSeconds; }
    public void setMaxEnvironmentSeconds(Double maxEnvironmentSeconds) { this.maxEnvironmentSeconds = maxEnvironmentSeconds; }

    public List<VmEstimate> getVmEstimates() { return vmEstimates; }
    public void setVmEstimates(List<VmEstimate> vmEstimates) { this.vmEstimates = vmEstimates; }
}
