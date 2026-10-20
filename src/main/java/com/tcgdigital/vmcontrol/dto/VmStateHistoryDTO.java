package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.User;
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
    private String changedByUsername;
    private String operationId;
    @com.tcgdigital.vmcontrol.dto.json.RedactProviderIds private String details;
    private Timestamp changedAt;
    private boolean isDrift;
    /**
     * For a change made by a run (U08-T07): what started the run (MANUAL, SCHEDULE, LEASE, …), the
     * rule's name, START/STOP and how many VMs it covered, so the VM's history can say
     * 'Schedule "Office hours"' or 'Priya Nair, as part of "Start 5 VMs"'. Null otherwise.
     */
    private String triggerSource;
    private String triggerName;
    private String operationType;
    private Integer operationTargets;

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

    /** Fill the person's name and the run's trigger, type and size (U08-T07). */
    public void describeRun(String username, com.tcgdigital.vmcontrol.model.OperationExecution run) {
        this.changedByUsername = username;
        if (run != null) {
            this.triggerSource = run.getTriggerSource() == null ? null : run.getTriggerSource().name();
            this.triggerName = run.getTriggerName();
            this.operationType = run.getOperationType() == null ? null : run.getOperationType().name();
            this.operationTargets = run.getTotalTargets();
        }
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    public String getTriggerName() {
        return triggerName;
    }

    public String getOperationType() {
        return operationType;
    }

    public Integer getOperationTargets() {
        return operationTargets;
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

    public String getChangedByUsername() {
        return changedByUsername;
    }

    public void setChangedByUsername(String changedByUsername) {
        this.changedByUsername = changedByUsername;
    }

    /**
     * Prefers {@link User#getUsername()}; if blank (e.g. an Azure AD-provisioned user that never
     * had a username set), infers one from the email's local part (the segment before {@code @}).
     * Returns null only when neither a username nor an email is available — callers render that
     * as "System", since a null {@code changedByUserId} row is always a {@code state_sync} event.
     */
    public static String resolveUsername(User user) {
        if (user == null) {
            return null;
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        String email = user.getEmail();
        if (email != null && email.contains("@")) {
            return email.substring(0, email.indexOf('@'));
        }
        return email;
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

