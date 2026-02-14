package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.EnvironmentLock;

import java.sql.Timestamp;

/**
 * DTO for lock status response.
 */
public class LockStatusDTO {

    private boolean locked;
    private String lockId;
    private String environmentId;
    private String lockedByUserId;
    private Timestamp lockedAt;
    private String lockReason;
    private Integer expectedDurationMinutes;

    public LockStatusDTO() {
    }

    public static LockStatusDTO noLock() {
        LockStatusDTO dto = new LockStatusDTO();
        dto.setLocked(false);
        return dto;
    }

    public static LockStatusDTO fromEntity(EnvironmentLock lock) {
        LockStatusDTO dto = new LockStatusDTO();
        dto.setLocked(true);
        dto.setLockId(lock.getLockId());
        dto.setEnvironmentId(lock.getEnvironment().getEnvironmentId());
        dto.setLockedByUserId(lock.getLockedByUserId());
        dto.setLockedAt(lock.getLockedAt());
        dto.setLockReason(lock.getLockReason());
        dto.setExpectedDurationMinutes(lock.getExpectedDurationMinutes());
        return dto;
    }

    // Getters and Setters
    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
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

    public String getLockedByUserId() {
        return lockedByUserId;
    }

    public void setLockedByUserId(String lockedByUserId) {
        this.lockedByUserId = lockedByUserId;
    }

    public Timestamp getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Timestamp lockedAt) {
        this.lockedAt = lockedAt;
    }

    public String getLockReason() {
        return lockReason;
    }

    public void setLockReason(String lockReason) {
        this.lockReason = lockReason;
    }

    public Integer getExpectedDurationMinutes() {
        return expectedDurationMinutes;
    }

    public void setExpectedDurationMinutes(Integer expectedDurationMinutes) {
        this.expectedDurationMinutes = expectedDurationMinutes;
    }
}

