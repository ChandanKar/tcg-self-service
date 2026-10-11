package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/**
 * An exhausted budget's action waiting for, or decided by, an owner (E20-T03): one per budget,
 * month and action. Nothing happens until it is APPROVED; unapproved requests expire at month end.
 */
@Entity
@Table(name = "budget_action_request")
public class BudgetActionRequest {

    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED }

    @Id
    @Column(name = "request_id", length = 36)
    private String requestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id", nullable = false)
    private Budget budget;

    @Column(name = "budget_month", nullable = false, length = 7, columnDefinition = "CHAR(7)")
    private String budgetMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private BudgetActionType actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "requested_at", nullable = false)
    @CreationTimestamp
    private Timestamp requestedAt;

    @Column(name = "decided_by_user_id", length = 36)
    private String decidedByUserId;

    @Column(name = "decided_at")
    private Timestamp decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    /** The STOP operation an approved stop action started (E20-T04). */
    @Column(name = "execution_id", length = 36)
    private String executionId;

    public BudgetActionRequest() {
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Budget getBudget() {
        return budget;
    }

    public void setBudget(Budget budget) {
        this.budget = budget;
    }

    public String getBudgetMonth() {
        return budgetMonth;
    }

    public void setBudgetMonth(String budgetMonth) {
        this.budgetMonth = budgetMonth;
    }

    public BudgetActionType getActionType() {
        return actionType;
    }

    public void setActionType(BudgetActionType actionType) {
        this.actionType = actionType;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Timestamp getRequestedAt() {
        return requestedAt;
    }

    public String getDecidedByUserId() {
        return decidedByUserId;
    }

    public void setDecidedByUserId(String decidedByUserId) {
        this.decidedByUserId = decidedByUserId;
    }

    public Timestamp getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Timestamp decidedAt) {
        this.decidedAt = decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void setDecisionReason(String decisionReason) {
        this.decisionReason = decisionReason;
    }

    public String getExecutionId() {
        return executionId;
    }

    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }
}
