package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;

/**
 * An instance-type change (E17-T04): APPLY from the app's resize, DETECTED by inventory sync.
 * A safe resize (E30-T06) is one row moving through {@link ResizeStatus}; only COMPLETED rows
 * are changes that happened.
 */
@Entity
@Table(name = "vm_resize_event")
public class VmResizeEvent {

    public enum Source { APPLY, DETECTED }

    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "vm_id", nullable = false, length = 36)
    private String vmId;

    @Column(name = "old_instance_type", nullable = false, length = 64)
    private String oldInstanceType;

    @Column(name = "new_instance_type", nullable = false, length = 64)
    private String newInstanceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private Source source;

    @Column(name = "changed_at", nullable = false)
    private Timestamp changedAt;

    @Column(name = "changed_by_user_id", length = 36)
    private String changedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ResizeStatus status = ResizeStatus.COMPLETED;

    /** What the workflow is doing, in words (E30-T06). */
    @Column(name = "stage", length = 100)
    private String stage;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** The admin asked to stop a running VM, resize and start it again. */
    @Column(name = "restart_requested", nullable = false)
    private boolean restartRequested;

    /** The VM's live status when the resize was requested. */
    @Column(name = "original_status", length = 20)
    private String originalStatus;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    public VmResizeEvent() {
    }

    public VmResizeEvent(String eventId, String vmId, String oldInstanceType, String newInstanceType, Source source,
                         Timestamp changedAt, String changedByUserId) {
        this.eventId = eventId;
        this.vmId = vmId;
        this.oldInstanceType = oldInstanceType;
        this.newInstanceType = newInstanceType;
        this.source = source;
        this.changedAt = changedAt;
        this.changedByUserId = changedByUserId;
    }

    public String getEventId() { return eventId; }
    public String getVmId() { return vmId; }
    public String getOldInstanceType() { return oldInstanceType; }
    public String getNewInstanceType() { return newInstanceType; }
    public Source getSource() { return source; }
    public Timestamp getChangedAt() { return changedAt; }
    public String getChangedByUserId() { return changedByUserId; }
    public void setChangedAt(Timestamp changedAt) { this.changedAt = changedAt; }
    public ResizeStatus getStatus() { return status; }
    public void setStatus(ResizeStatus status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public boolean isRestartRequested() { return restartRequested; }
    public void setRestartRequested(boolean restartRequested) { this.restartRequested = restartRequested; }
    public String getOriginalStatus() { return originalStatus; }
    public void setOriginalStatus(String originalStatus) { this.originalStatus = originalStatus; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }
}
