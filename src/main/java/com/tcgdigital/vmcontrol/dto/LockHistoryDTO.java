package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.LockAction;
import com.tcgdigital.vmcontrol.model.LockHistory;

import java.sql.Timestamp;

/**
 * DTO for lock history entries.
 */
public class LockHistoryDTO {

    private String historyId;
    private String lockId;
    private String environmentId;
    private LockAction action;
    private String performedByUserId;
    private Timestamp performedAt;
    private String notes;

    public LockHistoryDTO() {
    }

    public static LockHistoryDTO fromEntity(LockHistory history) {
        LockHistoryDTO dto = new LockHistoryDTO();
        dto.setHistoryId(history.getHistoryId());
        dto.setLockId(history.getLock().getLockId());
        dto.setEnvironmentId(history.getEnvironmentId());
        dto.setAction(history.getAction());
        dto.setPerformedByUserId(history.getPerformedByUserId());
        dto.setPerformedAt(history.getPerformedAt());
        dto.setNotes(history.getNotes());
        return dto;
    }

    // Getters and Setters
    public String getHistoryId() {
        return historyId;
    }

    public void setHistoryId(String historyId) {
        this.historyId = historyId;
    }

    public String getLockId() {
        return lockId;
    }

    public void setLockId(String lockId) {
        this.lockId = lockId;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public LockAction getAction() {
        return action;
    }

    public void setAction(LockAction action) {
        this.action = action;
    }

    public String getPerformedByUserId() {
        return performedByUserId;
    }

    public void setPerformedByUserId(String performedByUserId) {
        this.performedByUserId = performedByUserId;
    }

    public Timestamp getPerformedAt() {
        return performedAt;
    }

    public void setPerformedAt(Timestamp performedAt) {
        this.performedAt = performedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

