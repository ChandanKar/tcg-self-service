package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;

/** A user keeping a lease's environment running (E15); active while {@code leftAt} is null. */
@Entity
@Table(name = "environment_lease_participant")
@IdClass(EnvironmentLeaseParticipantId.class)
public class EnvironmentLeaseParticipant {

    @Id
    @Column(name = "lease_id", length = 36)
    private String leaseId;

    @Id
    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "joined_at", nullable = false)
    private Timestamp joinedAt;

    @Column(name = "left_at")
    private Timestamp leftAt;

    @Column(name = "left_reason", length = 100)
    private String leftReason;

    public EnvironmentLeaseParticipant() {
    }

    public EnvironmentLeaseParticipant(String leaseId, String userId, Timestamp joinedAt) {
        this.leaseId = leaseId;
        this.userId = userId;
        this.joinedAt = joinedAt;
    }

    public String getLeaseId() { return leaseId; }
    public String getUserId() { return userId; }
    public Timestamp getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Timestamp joinedAt) { this.joinedAt = joinedAt; }
    public Timestamp getLeftAt() { return leftAt; }
    public void setLeftAt(Timestamp leftAt) { this.leftAt = leftAt; }
    public String getLeftReason() { return leftReason; }
    public void setLeftReason(String leftReason) { this.leftReason = leftReason; }
}
