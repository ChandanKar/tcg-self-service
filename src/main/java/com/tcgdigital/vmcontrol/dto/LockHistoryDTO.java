package com.tcgdigital.vmcontrol.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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
    private String userDisplayName;
    private Timestamp performedAt;
    private String notes;
    
    // Fields from the parent EnvironmentLock for duration calculation
    private Timestamp acquiredAt;
    private Timestamp releasedAt;
    private String lockReason;
    /** Why an admin broke the lock (BROKEN rows; E07-T05). */
    private String breakReason;

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
        
        // Get lock timing info from parent lock
        if (history.getLock() != null) {
            dto.setAcquiredAt(history.getLock().getLockedAt());
            dto.setReleasedAt(history.getLock().getReleasedAt());
            dto.setLockReason(history.getLock().getLockReason());
            dto.setBreakReason(history.getLock().getBreakReason());
        }
        
        return dto;
    }

    /**
     * Create DTO from entity with user display name.
     */
    public static LockHistoryDTO fromEntity(LockHistory history, String displayName) {
        LockHistoryDTO dto = fromEntity(history);
        dto.setUserDisplayName(displayName);
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

    /**
     * Alias for performedByUserId for frontend compatibility.
     */
    @JsonProperty("userId")
    public String getUserId() {
        return performedByUserId;
    }

    public String getUserDisplayName() {
        return userDisplayName;
    }

    public void setUserDisplayName(String userDisplayName) {
        this.userDisplayName = userDisplayName;
    }

    public Timestamp getPerformedAt() {
        return performedAt;
    }

    public void setPerformedAt(Timestamp performedAt) {
        this.performedAt = performedAt;
    }

    /**
     * Alias for performedAt for frontend compatibility.
     */
    @JsonProperty("timestamp")
    public Timestamp getTimestamp() {
        return performedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    /**
     * The row's own reason (E07-T05): why the lock was taken on ACQUIRED, why it was broken on
     * BROKEN, otherwise the row's notes (auto-release, expiry or extension details).
     */
    @JsonProperty("reason")
    public String getReason() {
        if (action == LockAction.ACQUIRED) {
            return lockReason != null ? lockReason : notes;
        }
        if (action == LockAction.BROKEN) {
            return breakReason != null ? breakReason : notes;
        }
        return notes;
    }

    public String getBreakReason() {
        return breakReason;
    }

    public void setBreakReason(String breakReason) {
        this.breakReason = breakReason;
    }

    public Timestamp getAcquiredAt() {
        return acquiredAt;
    }

    public void setAcquiredAt(Timestamp acquiredAt) {
        this.acquiredAt = acquiredAt;
    }

    public Timestamp getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Timestamp releasedAt) {
        this.releasedAt = releasedAt;
    }

    public String getLockReason() {
        return lockReason;
    }

    public void setLockReason(String lockReason) {
        this.lockReason = lockReason;
    }
}
