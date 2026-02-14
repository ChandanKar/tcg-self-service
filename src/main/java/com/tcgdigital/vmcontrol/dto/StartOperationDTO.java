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
     * Whether to continue on failure of individual VMs.
     */
    private boolean continueOnFailure = false;

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

