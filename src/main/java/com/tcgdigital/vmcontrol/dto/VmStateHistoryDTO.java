package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;

import java.sql.Timestamp;

/**
 * DTO for VM state history response.
 */
public class VmStateHistoryDTO {

    private String historyId;
    private String vmId;
    private String vmName;
    private VmStatus previousStatus;
    private VmStatus newStatus;
    private String changeSource;
    private String changedByUserId;
    private String operationId;
    private String details;
    private Timestamp changedAt;
    private boolean isDrift;

    public VmStateHistoryDTO() {
    }

    public static VmStateHistoryDTO fromEntity(VmStateHistory history) {
        VmStateHistoryDTO dto = new VmStateHistoryDTO();
        dto.setHistoryId(history.getHistoryId());
        dto.setVmId(history.getVm() != null ? history.getVm().getVmId() : null);
        dto.setVmName(history.getVm() != null ? history.getVm().getName() : null);
        dto.setPreviousStatus(history.getPreviousStatus());
        dto.setNewStatus(history.getNewStatus());
        dto.setChangeSource(history.getChangeSource());
        dto.setChangedByUserId(history.getChangedByUserId());
        dto.setOperationId(history.getOperationId());
        dto.setDetails(history.getDetails());
        dto.setChangedAt(history.getChangedAt());
        dto.setDrift("state_sync".equals(history.getChangeSource()));
        return dto;
    }

    // Getters and Setters
    public String getHistoryId() {
        return historyId;
    }

    public void setHistoryId(String historyId) {
        this.historyId = historyId;
    }

    public String getVmId() {
        return vmId;
    }

    public void setVmId(String vmId) {
        this.vmId = vmId;
    }

    public String getVmName() {
        return vmName;
    }

    public void setVmName(String vmName) {
        this.vmName = vmName;
    }

    public VmStatus getPreviousStatus() {
        return previousStatus;
    }

    public void setPreviousStatus(VmStatus previousStatus) {
        this.previousStatus = previousStatus;
    }

    public VmStatus getNewStatus() {
        return newStatus;
    }

    public void setNewStatus(VmStatus newStatus) {
        this.newStatus = newStatus;
    }

    public String getChangeSource() {
        return changeSource;
    }

    public void setChangeSource(String changeSource) {
        this.changeSource = changeSource;
    }

    public String getChangedByUserId() {
        return changedByUserId;
    }

    public void setChangedByUserId(String changedByUserId) {
        this.changedByUserId = changedByUserId;
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Timestamp getChangedAt() {
        return changedAt;
    }

    public void setChangedAt(Timestamp changedAt) {
        this.changedAt = changedAt;
    }

    public boolean isDrift() {
        return isDrift;
    }

    public void setDrift(boolean drift) {
        isDrift = drift;
    }
}

