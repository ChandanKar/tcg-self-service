package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * One day's account-wide Reserved Instance / Savings Plan coverage and utilization — not
 * per-VM/per-tag, since Cost Explorer's reservation/savings-plan APIs are account-wide. Any
 * field may be null (e.g. {@code riUtilizationPercent} when the account has no RIs at all) —
 * never fabricated as zero.
 */
@Entity
@Table(name = "reservation_coverage_snapshot")
public class ReservationCoverageSnapshot {

    @Id
    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    @Column(name = "snapshot_date", nullable = false)
    private Date snapshotDate;

    @Column(name = "on_demand_cost", precision = 12, scale = 2)
    private BigDecimal onDemandCost;

    @Column(name = "covered_cost", precision = 12, scale = 2)
    private BigDecimal coveredCost;

    @Column(name = "coverage_percent", precision = 5, scale = 2)
    private BigDecimal coveragePercent;

    @Column(name = "ri_utilization_percent", precision = 5, scale = 2)
    private BigDecimal riUtilizationPercent;

    @Column(name = "sp_coverage_percent", precision = 5, scale = 2)
    private BigDecimal spCoveragePercent;

    @Column(name = "sp_utilization_percent", precision = 5, scale = 2)
    private BigDecimal spUtilizationPercent;

    @Column(name = "net_savings", precision = 12, scale = 2)
    private BigDecimal netSavings;

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
    public Date getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(Date snapshotDate) { this.snapshotDate = snapshotDate; }
    public BigDecimal getOnDemandCost() { return onDemandCost; }
    public void setOnDemandCost(BigDecimal onDemandCost) { this.onDemandCost = onDemandCost; }
    public BigDecimal getCoveredCost() { return coveredCost; }
    public void setCoveredCost(BigDecimal coveredCost) { this.coveredCost = coveredCost; }
    public BigDecimal getCoveragePercent() { return coveragePercent; }
    public void setCoveragePercent(BigDecimal coveragePercent) { this.coveragePercent = coveragePercent; }
    public BigDecimal getRiUtilizationPercent() { return riUtilizationPercent; }
    public void setRiUtilizationPercent(BigDecimal riUtilizationPercent) { this.riUtilizationPercent = riUtilizationPercent; }
    public BigDecimal getSpCoveragePercent() { return spCoveragePercent; }
    public void setSpCoveragePercent(BigDecimal spCoveragePercent) { this.spCoveragePercent = spCoveragePercent; }
    public BigDecimal getSpUtilizationPercent() { return spUtilizationPercent; }
    public void setSpUtilizationPercent(BigDecimal spUtilizationPercent) { this.spUtilizationPercent = spUtilizationPercent; }
    public BigDecimal getNetSavings() { return netSavings; }
    public void setNetSavings(BigDecimal netSavings) { this.netSavings = netSavings; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}
