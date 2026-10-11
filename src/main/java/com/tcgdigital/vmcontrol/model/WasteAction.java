package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;

/**
 * A fix for a waste finding (E22-T04): proposed by one admin, approved by another, executed in the
 * background, and rolled back from {@code rollbackData} where AWS allows it.
 */
@Entity
@Table(name = "waste_action")
public class WasteAction {

    public enum Type {
        /** Snapshot an unattached volume, then delete it; rollback recreates it from the snapshot. */
        DELETE_VOLUME,
        /** Change a gp2 volume to gp3; rollback changes it back. */
        MIGRATE_GP3,
        /** Move a snapshot to the archive tier; rollback restores it. */
        ARCHIVE_SNAPSHOT,
        /** Release an Elastic IP; cannot be rolled back. */
        RELEASE_IP
    }

    public enum Status { PROPOSED, APPROVED, EXECUTING, EXECUTED, FAILED, REJECTED, ROLLED_BACK }

    @Id
    @Column(name = "action_id", length = 36)
    private String actionId;

    @Column(name = "finding_id", nullable = false, length = 36)
    private String findingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 30)
    private Type actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PROPOSED;

    @Column(name = "proposed_by", nullable = false, length = 36)
    private String proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private Timestamp proposedAt;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "approved_at")
    private Timestamp approvedAt;

    @Column(name = "executed_at")
    private Timestamp executedAt;

    @Column(name = "rolled_back_at")
    private Timestamp rolledBackAt;

    /** JSON with what a rollback needs (snapshot id, AZ, volume type and size, tags...). */
    @Column(name = "rollback_data", columnDefinition = "TEXT")
    private String rollbackData;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public WasteAction() {
    }

    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }
    public String getFindingId() { return findingId; }
    public void setFindingId(String findingId) { this.findingId = findingId; }
    public Type getActionType() { return actionType; }
    public void setActionType(Type actionType) { this.actionType = actionType; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getProposedBy() { return proposedBy; }
    public void setProposedBy(String proposedBy) { this.proposedBy = proposedBy; }
    public Timestamp getProposedAt() { return proposedAt; }
    public void setProposedAt(Timestamp proposedAt) { this.proposedAt = proposedAt; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public Timestamp getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Timestamp approvedAt) { this.approvedAt = approvedAt; }
    public Timestamp getExecutedAt() { return executedAt; }
    public void setExecutedAt(Timestamp executedAt) { this.executedAt = executedAt; }
    public Timestamp getRolledBackAt() { return rolledBackAt; }
    public void setRolledBackAt(Timestamp rolledBackAt) { this.rolledBackAt = rolledBackAt; }
    public String getRollbackData() { return rollbackData; }
    public void setRollbackData(String rollbackData) { this.rollbackData = rollbackData; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Long getVersion() { return version; }
}
