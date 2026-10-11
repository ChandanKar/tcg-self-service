package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;

/** An AWS Cost Anomaly Detection anomaly, mapped to an environment by its monitor (E21-T02). */
@Entity
@Table(name = "cost_anomaly")
public class CostAnomaly {

    /** The AWS AnomalyId. */
    @Id
    @Column(name = "anomaly_id", length = 100)
    private String anomalyId;

    @Column(name = "monitor_arn", length = 255)
    private String monitorArn;

    /** Null when its monitor is not linked to an environment (admins only). */
    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(name = "anomaly_start")
    private LocalDate anomalyStart;

    @Column(name = "anomaly_end")
    private LocalDate anomalyEnd;

    /** Spend above the expected amount, USD. */
    @Column(name = "total_impact", precision = 12, scale = 2)
    private BigDecimal totalImpact;

    @Column(name = "total_expected", precision = 12, scale = 2)
    private BigDecimal totalExpected;

    @Column(name = "max_score", precision = 6, scale = 2)
    private BigDecimal maxScore;

    /** JSON list of {service, region, usageType, linkedAccount}. */
    @Column(name = "root_causes", columnDefinition = "TEXT")
    private String rootCauses;

    /** The owner's verdict (E21-T03): YES, NO or PLANNED_ACTIVITY. */
    @Column(name = "feedback", length = 20)
    private String feedback;

    @Column(name = "first_seen_at", nullable = false)
    private Timestamp firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Timestamp lastSeenAt;

    /** When the owners were told (E21-T03); null until then. */
    @Column(name = "notified_at")
    private Timestamp notifiedAt;

    public CostAnomaly() {
    }

    public String getAnomalyId() { return anomalyId; }
    public void setAnomalyId(String anomalyId) { this.anomalyId = anomalyId; }
    public String getMonitorArn() { return monitorArn; }
    public void setMonitorArn(String monitorArn) { this.monitorArn = monitorArn; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public LocalDate getAnomalyStart() { return anomalyStart; }
    public void setAnomalyStart(LocalDate anomalyStart) { this.anomalyStart = anomalyStart; }
    public LocalDate getAnomalyEnd() { return anomalyEnd; }
    public void setAnomalyEnd(LocalDate anomalyEnd) { this.anomalyEnd = anomalyEnd; }
    public BigDecimal getTotalImpact() { return totalImpact; }
    public void setTotalImpact(BigDecimal totalImpact) { this.totalImpact = totalImpact; }
    public BigDecimal getTotalExpected() { return totalExpected; }
    public void setTotalExpected(BigDecimal totalExpected) { this.totalExpected = totalExpected; }
    public BigDecimal getMaxScore() { return maxScore; }
    public void setMaxScore(BigDecimal maxScore) { this.maxScore = maxScore; }
    public String getRootCauses() { return rootCauses; }
    public void setRootCauses(String rootCauses) { this.rootCauses = rootCauses; }
    public String getFeedback() { return feedback; }
    public void setFeedback(String feedback) { this.feedback = feedback; }
    public Timestamp getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Timestamp firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Timestamp getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Timestamp lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Timestamp getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(Timestamp notifiedAt) { this.notifiedAt = notifiedAt; }
}
