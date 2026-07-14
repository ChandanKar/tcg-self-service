package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * One environment's cost for one calendar day — feeds the Cost Management spend trend chart.
 * {@code actualCost} is reserved for a future real-billing provider; always null today.
 */
@Entity
@Table(name = "cost_daily_snapshot")
public class CostDailySnapshot {

    @Id
    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    @Column(name = "snapshot_date", nullable = false)
    private Date snapshotDate;

    @Column(name = "estimated_cost", nullable = false, precision = 12, scale = 2)
    private BigDecimal estimatedCost;

    @Column(name = "actual_cost", precision = 12, scale = 2)
    private BigDecimal actualCost;

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
    public Date getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(Date snapshotDate) { this.snapshotDate = snapshotDate; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public void setEstimatedCost(BigDecimal estimatedCost) { this.estimatedCost = estimatedCost; }
    public BigDecimal getActualCost() { return actualCost; }
    public void setActualCost(BigDecimal actualCost) { this.actualCost = actualCost; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}
