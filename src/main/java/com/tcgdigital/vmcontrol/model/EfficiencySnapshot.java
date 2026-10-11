package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;

/** One day's Cost Efficiency % for the fleet (environmentId null) or one environment (E17-T05). */
@Entity
@Table(name = "efficiency_snapshot")
public class EfficiencySnapshot {

    @Id
    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(name = "team", length = 100)
    private String team;

    @Column(name = "potential_savings", nullable = false, precision = 12, scale = 2)
    private BigDecimal potentialSavings;

    @Column(name = "optimisable_spend", nullable = false, precision = 12, scale = 2)
    private BigDecimal optimisableSpend;

    @Column(name = "efficiency_percent", precision = 5, scale = 1)
    private BigDecimal efficiencyPercent;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "scope_key", length = 36, insertable = false, updatable = false)
    private String scopeKey;

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }
    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public String getTeam() { return team; }
    public void setTeam(String team) { this.team = team; }
    public BigDecimal getPotentialSavings() { return potentialSavings; }
    public void setPotentialSavings(BigDecimal potentialSavings) { this.potentialSavings = potentialSavings; }
    public BigDecimal getOptimisableSpend() { return optimisableSpend; }
    public void setOptimisableSpend(BigDecimal optimisableSpend) { this.optimisableSpend = optimisableSpend; }
    public BigDecimal getEfficiencyPercent() { return efficiencyPercent; }
    public void setEfficiencyPercent(BigDecimal efficiencyPercent) { this.efficiencyPercent = efficiencyPercent; }
    public Timestamp getCreatedAt() { return createdAt; }
}
