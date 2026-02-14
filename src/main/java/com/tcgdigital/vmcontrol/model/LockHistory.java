package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/**
 * LockHistory - audit trail for lock operations.
 */
@Entity
@Table(name = "lock_history")
public class LockHistory {

    @Id
    @Column(name = "history_id", length = 36)
    private String historyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lock_id", nullable = false)
    private EnvironmentLock lock;

    @Column(name = "environment_id", nullable = false, length = 36)
    private String environmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LockAction action;

    @Column(name = "performed_by_user_id", nullable = false, length = 36)
    private String performedByUserId;

    @Column(name = "performed_at", nullable = false)
    @CreationTimestamp
    private Timestamp performedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    public LockHistory() {
    }

    // Getters and Setters
    public String getHistoryId() {
        return historyId;
    }

    public void setHistoryId(String historyId) {
        this.historyId = historyId;
    }

    public EnvironmentLock getLock() {
        return lock;
    }

    public void setLock(EnvironmentLock lock) {
        this.lock = lock;
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

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}

