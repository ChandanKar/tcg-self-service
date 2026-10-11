package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;

/** An instance-type change (E17-T04): APPLY from the app's resize, DETECTED by inventory sync. */
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
}
