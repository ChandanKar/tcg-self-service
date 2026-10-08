package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** One idle-stop decision with its evidence (E16): would stop, stopped, skipped or snoozed. */
@Entity
@Table(name = "idle_stop_event")
public class IdleStopEvent {

    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "rule_id", nullable = false, length = 36)
    private String ruleId;

    @Column(name = "environment_id", nullable = false, length = 36)
    private String environmentId;

    @Column(name = "evaluated_at", nullable = false)
    private Timestamp evaluatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private IdleStopOutcome outcome;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "idle_since")
    private Timestamp idleSince;

    @Column(name = "evidence_json", columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(name = "projected_saving_per_hour", precision = 12, scale = 4)
    private BigDecimal projectedSavingPerHour;

    @Column(name = "execution_id", length = 36)
    private String executionId;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public Timestamp getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Timestamp evaluatedAt) { this.evaluatedAt = evaluatedAt; }
    public IdleStopOutcome getOutcome() { return outcome; }
    public void setOutcome(IdleStopOutcome outcome) { this.outcome = outcome; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Timestamp getIdleSince() { return idleSince; }
    public void setIdleSince(Timestamp idleSince) { this.idleSince = idleSince; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public BigDecimal getProjectedSavingPerHour() { return projectedSavingPerHour; }
    public void setProjectedSavingPerHour(BigDecimal projectedSavingPerHour) { this.projectedSavingPerHour = projectedSavingPerHour; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
}
