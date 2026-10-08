package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * Idle auto-stop rule for one environment, or one group of it (E16, G5): stop the running VMs in
 * scope once all of them have been idle for {@code idleMinutes}.
 */
@Entity
@Table(name = "idle_stop_rule")
public class IdleStopRule {

    @Id
    @Column(name = "rule_id", length = 36)
    private String ruleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    /** ENVIRONMENT or GROUP. */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private AutomationScopeType scopeType = AutomationScopeType.ENVIRONMENT;

    @Column(name = "group_id", length = 36)
    private String groupId;

    @Column(name = "idle_minutes", nullable = false)
    private Integer idleMinutes = 60;

    @Column(name = "cpu_max_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal cpuMaxPercent = new BigDecimal("5.00");

    @Column(name = "network_mb_per_day", nullable = false, precision = 10, scale = 2)
    private BigDecimal networkMbPerDay = new BigDecimal("5.00");

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private IdleStopMode mode = IdleStopMode.DRY_RUN;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** When the rule (last) entered DRY_RUN; ENFORCE needs a long enough dry run first. */
    @Column(name = "dry_run_started_at")
    private Timestamp dryRunStartedAt;

    @Column(name = "created_by_user_id", nullable = false, length = 36)
    private String createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment environment) { this.environment = environment; }
    public AutomationScopeType getScopeType() { return scopeType; }
    public void setScopeType(AutomationScopeType scopeType) { this.scopeType = scopeType; }
    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }
    public Integer getIdleMinutes() { return idleMinutes; }
    public void setIdleMinutes(Integer idleMinutes) { this.idleMinutes = idleMinutes; }
    public BigDecimal getCpuMaxPercent() { return cpuMaxPercent; }
    public void setCpuMaxPercent(BigDecimal cpuMaxPercent) { this.cpuMaxPercent = cpuMaxPercent; }
    public BigDecimal getNetworkMbPerDay() { return networkMbPerDay; }
    public void setNetworkMbPerDay(BigDecimal networkMbPerDay) { this.networkMbPerDay = networkMbPerDay; }
    public IdleStopMode getMode() { return mode; }
    public void setMode(IdleStopMode mode) { this.mode = mode; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Timestamp getDryRunStartedAt() { return dryRunStartedAt; }
    public void setDryRunStartedAt(Timestamp dryRunStartedAt) { this.dryRunStartedAt = dryRunStartedAt; }
    public String getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(String createdByUserId) { this.createdByUserId = createdByUserId; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
