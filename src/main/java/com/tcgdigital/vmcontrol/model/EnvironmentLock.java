package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;

/**
 * EnvironmentLock - environment-wide exclusive lock.
 * Only one active lock per environment is allowed.
 */
@Entity
@Table(name = "environment_lock")
public class EnvironmentLock {

    @Id
    @Column(name = "lock_id", length = 36)
    private String lockId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    @Column(name = "locked_by_user_id", nullable = false, length = 36)
    private String lockedByUserId;

    @Column(name = "locked_at", nullable = false)
    @CreationTimestamp
    private Timestamp lockedAt;

    @Column(name = "lock_reason", columnDefinition = "TEXT")
    private String lockReason;

    @Column(name = "expected_duration_minutes")
    private Integer expectedDurationMinutes;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "released_at")
    private Timestamp releasedAt;

    @Column(name = "released_by_user_id", length = 36)
    private String releasedByUserId;

    @Column(name = "broken_by_admin_user_id", length = 36)
    private String brokenByAdminUserId;

    @Column(name = "break_reason", columnDefinition = "TEXT")
    private String breakReason;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    public EnvironmentLock() {
    }

    // Getters and Setters
    public String getLockId() {
        return lockId;
    }

    public void setLockId(String lockId) {
        this.lockId = lockId;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
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

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Timestamp getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Timestamp releasedAt) {
        this.releasedAt = releasedAt;
    }

    public String getReleasedByUserId() {
        return releasedByUserId;
    }

    public void setReleasedByUserId(String releasedByUserId) {
        this.releasedByUserId = releasedByUserId;
    }

    public String getBrokenByAdminUserId() {
        return brokenByAdminUserId;
    }

    public void setBrokenByAdminUserId(String brokenByAdminUserId) {
        this.brokenByAdminUserId = brokenByAdminUserId;
    }

    public String getBreakReason() {
        return breakReason;
    }

    public void setBreakReason(String breakReason) {
        this.breakReason = breakReason;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Check if the lock was broken by an admin.
     */
    public boolean wasBroken() {
        return brokenByAdminUserId != null;
    }
}

