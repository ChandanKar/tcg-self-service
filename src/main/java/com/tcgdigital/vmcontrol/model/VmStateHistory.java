package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/**
 * VmStateHistory - tracks VM state changes over time.
 * Maps to existing vm_state_history table from V1 schema.
 */
@Entity
@Table(name = "vm_state_history")
public class VmStateHistory {

    @Id
    @Column(name = "history_id", length = 36)
    private String historyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vm_id", nullable = false)
    private Vm vm;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20)
    private VmStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 20)
    private VmStatus newStatus;

    @Column(name = "change_source", nullable = false, length = 20)
    private String changeSource; // 'user_action', 'state_sync', 'cloud_event', 'system'

    @Column(name = "changed_by_user_id", length = 36)
    private String changedByUserId;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String details; // Stored as metadata in V1 schema

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Timestamp changedAt; // Maps to created_at in V1 schema

    // Transient field for operation tracking (not in V1 schema)
    @Transient
    private String operationId;

    public VmStateHistory() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final VmStateHistory history = new VmStateHistory();

        public Builder historyId(String historyId) {
            history.historyId = historyId;
            return this;
        }

        public Builder vm(Vm vm) {
            history.vm = vm;
            return this;
        }

        public Builder previousStatus(VmStatus previousStatus) {
            history.previousStatus = previousStatus;
            return this;
        }

        public Builder newStatus(VmStatus newStatus) {
            history.newStatus = newStatus;
            return this;
        }

        public Builder changeSource(String changeSource) {
            history.changeSource = changeSource;
            return this;
        }

        public Builder changedByUserId(String changedByUserId) {
            history.changedByUserId = changedByUserId;
            return this;
        }

        public Builder operationId(String operationId) {
            history.operationId = operationId;
            // Also store in details/metadata for persistence
            if (operationId != null && history.details != null) {
                history.details = history.details + " | operationId: " + operationId;
            } else if (operationId != null) {
                history.details = "operationId: " + operationId;
            }
            return this;
        }

        public Builder details(String details) {
            history.details = details;
            return this;
        }

        public VmStateHistory build() {
            return history;
        }
    }

    // Getters and Setters
    public String getHistoryId() {
        return historyId;
    }

    public void setHistoryId(String historyId) {
        this.historyId = historyId;
    }

    public Vm getVm() {
        return vm;
    }

    public void setVm(Vm vm) {
        this.vm = vm;
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
}

