package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.NotNull;
import com.tcgdigital.vmcontrol.model.OperationType;

import java.util.List;

/**
 * DTO for initiating a VM operation on an environment.
 */
public class StartOperationDTO {

    @NotNull(message = "Operation type is required")
    private OperationType operationType;

    private String reason;

    /**
     * Optional: specific VM IDs to operate on.
     * If null/empty, all VMs in environment will be affected.
     */
    private List<String> vmIds;

    /**
     * Optional: specific group IDs to operate on.
     * If null/empty, all groups in environment will be affected.
     */
    private List<String> groupIds;

    /**
     * Whether to skip VMs that are already in target state.
     */
    private boolean skipAlreadyInTargetState = true;

    /**
     * Keep running independent steps after a step fails (default). When false, the first wave
     * with a failure ends the run and every remaining step is skipped. Dependents of a failed
     * step are skipped either way.
     */
    private boolean continueOnFailure = true;

    /**
     * How long a START should keep a lease-enabled environment running (E15-T02); null = the
     * owner's default. Bounded by the environment's maximum on the server.
     */
    @jakarta.validation.constraints.Min(value = 15, message = "A lease is at least 15 minutes")
    @jakarta.validation.constraints.Max(value = 4320, message = "A lease is at most 4320 minutes")
    private Integer leaseMinutes;

    public Integer getLeaseMinutes() {
        return leaseMinutes;
    }

    public void setLeaseMinutes(Integer leaseMinutes) {
        this.leaseMinutes = leaseMinutes;
    }

    public StartOperationDTO() {
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(OperationType operationType) {
        this.operationType = operationType;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<String> getVmIds() {
        return vmIds;
    }

    public void setVmIds(List<String> vmIds) {
        this.vmIds = vmIds;
    }

    public List<String> getGroupIds() {
        return groupIds;
    }

    public void setGroupIds(List<String> groupIds) {
        this.groupIds = groupIds;
    }

    public boolean isSkipAlreadyInTargetState() {
        return skipAlreadyInTargetState;
    }

    public void setSkipAlreadyInTargetState(boolean skipAlreadyInTargetState) {
        this.skipAlreadyInTargetState = skipAlreadyInTargetState;
    }

    public boolean isContinueOnFailure() {
        return continueOnFailure;
    }

    public void setContinueOnFailure(boolean continueOnFailure) {
        this.continueOnFailure = continueOnFailure;
    }
}

