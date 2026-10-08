package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/** Pauses idle auto-stop for an environment until {@code snoozedUntil} (E16). */
@Entity
@Table(name = "idle_stop_snooze")
public class IdleStopSnooze {

    @Id
    @Column(name = "snooze_id", length = 36)
    private String snoozeId;

    @Column(name = "environment_id", nullable = false, length = 36)
    private String environmentId;

    @Column(name = "snoozed_until", nullable = false)
    private Timestamp snoozedUntil;

    @Column(name = "snoozed_by_user_id", length = 36)
    private String snoozedByUserId;

    @Column(name = "reason", length = 255)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    public String getSnoozeId() { return snoozeId; }
    public void setSnoozeId(String snoozeId) { this.snoozeId = snoozeId; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public Timestamp getSnoozedUntil() { return snoozedUntil; }
    public void setSnoozedUntil(Timestamp snoozedUntil) { this.snoozedUntil = snoozedUntil; }
    public String getSnoozedByUserId() { return snoozedByUserId; }
    public void setSnoozedByUserId(String snoozedByUserId) { this.snoozedByUserId = snoozedByUserId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Timestamp getCreatedAt() { return createdAt; }
}
