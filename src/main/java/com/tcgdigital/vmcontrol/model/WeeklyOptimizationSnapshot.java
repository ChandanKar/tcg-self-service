package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * One environment's total for one weekly optimization report ({@code IDLE_WASTE} or {@code
 * RIGHTSIZING}) on one week's snapshot date — the baseline the following week's report diffs
 * against, since neither idle-waste nor rightsizing has historical storage otherwise (unlike
 * cost, which already has {@link CostDailySnapshot}).
 */
@Entity
@Table(name = "weekly_optimization_snapshot")
public class WeeklyOptimizationSnapshot {

    @Id
    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 20)
    private WeeklyOptimizationReportType reportType;

    @Column(name = "snapshot_date", nullable = false)
    private Date snapshotDate;

    @Column(name = "metric_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal metricValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @PrePersist
    protected void onCreate() {
        if (snapshotId == null) {
            snapshotId = UUID.randomUUID().toString();
        }
    }

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }
    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment environment) { this.environment = environment; }
    public WeeklyOptimizationReportType getReportType() { return reportType; }
    public void setReportType(WeeklyOptimizationReportType reportType) { this.reportType = reportType; }
    public Date getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(Date snapshotDate) { this.snapshotDate = snapshotDate; }
    public BigDecimal getMetricValue() { return metricValue; }
    public void setMetricValue(BigDecimal metricValue) { this.metricValue = metricValue; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}
