package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Objects;

/** One approver's decision on an approval request (E25-T04); one per approver. */
@Entity
@Table(name = "approval_decision")
@IdClass(ApprovalRequestDecision.Key.class)
public class ApprovalRequestDecision {

    public enum Decision { APPROVED, DENIED }

    public static class Key implements Serializable {
        private String requestId;
        private String approverUserId;

        public Key() {
        }

        public Key(String requestId, String approverUserId) {
            this.requestId = requestId;
            this.approverUserId = approverUserId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(requestId, k.requestId) && Objects.equals(approverUserId, k.approverUserId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(requestId, approverUserId);
        }
    }

    @Id
    @Column(name = "request_id", length = 36)
    private String requestId;

    @Id
    @Column(name = "approver_user_id", length = 36)
    private String approverUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision", nullable = false, length = 10)
    private Decision decision;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "decided_at", nullable = false)
    private Timestamp decidedAt;

    public ApprovalRequestDecision() {
    }

    public ApprovalRequestDecision(String requestId, String approverUserId, Decision decision, String reason, Timestamp decidedAt) {
        this.requestId = requestId;
        this.approverUserId = approverUserId;
        this.decision = decision;
        this.reason = reason;
        this.decidedAt = decidedAt;
    }

    public String getRequestId() { return requestId; }
    public String getApproverUserId() { return approverUserId; }
    public Decision getDecision() { return decision; }
    public String getReason() { return reason; }
    public Timestamp getDecidedAt() { return decidedAt; }
}
