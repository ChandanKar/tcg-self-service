package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** A team's monthly allowance of running VM-hours or dollars (E25-T07); one per metric. */
@Entity
@Table(name = "team_quota")
public class TeamQuota {

    public enum Metric { VM_HOURS, COST }

    /** What happens when a start would go past the limit. */
    public enum Enforcement { WARN, REQUIRE_APPROVAL, BLOCK }

    @Id
    @Column(name = "quota_id", length = 36)
    private String quotaId;

    @Column(name = "team_id", nullable = false, length = 36)
    private String teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric", nullable = false, length = 10)
    private Metric metric;

    @Column(name = "limit_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal limitValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "enforcement", nullable = false, length = 20)
    private Enforcement enforcement;

    @Column(name = "warn_percent", nullable = false)
    private Integer warnPercent = 80;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    public TeamQuota() {
    }

    public String getQuotaId() { return quotaId; }
    public void setQuotaId(String quotaId) { this.quotaId = quotaId; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public Metric getMetric() { return metric; }
    public void setMetric(Metric metric) { this.metric = metric; }
    public BigDecimal getLimitValue() { return limitValue; }
    public void setLimitValue(BigDecimal limitValue) { this.limitValue = limitValue; }
    public Enforcement getEnforcement() { return enforcement; }
    public void setEnforcement(Enforcement enforcement) { this.enforcement = enforcement; }
    public Integer getWarnPercent() { return warnPercent; }
    public void setWarnPercent(Integer warnPercent) { this.warnPercent = warnPercent; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
}
