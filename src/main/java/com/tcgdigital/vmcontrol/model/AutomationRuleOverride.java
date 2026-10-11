package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/**
 * Skips a schedule rule's actions whose due time falls in [startsAt, endsAt) (E19-T03), e.g.
 * "keep running tonight for a release".
 */
@Entity
@Table(name = "automation_rule_override")
public class AutomationRuleOverride {

    /** Which of the rule's actions an override skips. */
    public enum SkipAction { START, STOP, ALL }

    @Id
    @Column(name = "override_id", length = 36)
    private String overrideId;

    @Column(name = "rule_id", nullable = false, length = 36)
    private String ruleId;

    @Column(name = "starts_at", nullable = false)
    private Timestamp startsAt;

    @Column(name = "ends_at", nullable = false)
    private Timestamp endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "skip_action", nullable = false, length = 10)
    private SkipAction skipAction;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "created_by_user_id", nullable = false, length = 36)
    private String createdByUserId;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    public AutomationRuleOverride() {
    }

    /** Whether the override skips {@code type} (START or STOP). */
    public boolean skips(OperationType type) {
        return skipAction == SkipAction.ALL || skipAction.name().equals(type.name());
    }

    public String getOverrideId() {
        return overrideId;
    }

    public void setOverrideId(String overrideId) {
        this.overrideId = overrideId;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public Timestamp getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Timestamp startsAt) {
        this.startsAt = startsAt;
    }

    public Timestamp getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Timestamp endsAt) {
        this.endsAt = endsAt;
    }

    public SkipAction getSkipAction() {
        return skipAction;
    }

    public void setSkipAction(SkipAction skipAction) {
        this.skipAction = skipAction;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(String createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }
}
