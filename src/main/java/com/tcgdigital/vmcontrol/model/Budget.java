package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** A monthly budget for an environment or a team, with alert thresholds and an optional action (E20-T01). */
@Entity
@Table(name = "budget")
public class Budget {

    @Id
    @Column(name = "budget_id", length = 36)
    private String budgetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private BudgetScopeType scopeType;

    /** An environment id, or the team name as TeamResolver returns it. */
    @Column(name = "scope_id", nullable = false, length = 100)
    private String scopeId;

    @Column(name = "monthly_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal monthlyAmount;

    /** Sorted percentages, e.g. "50,75,90,100". */
    @Column(name = "thresholds", nullable = false, length = 50)
    private String thresholds = "50,75,90,100";

    @Column(name = "forecast_alert", nullable = false)
    private Boolean forecastAlert = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 20)
    private BudgetActionType actionType = BudgetActionType.NONE;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "aws_budget_name", length = 100)
    private String awsBudgetName;

    @Column(name = "created_by_user_id", nullable = false, length = 36)
    private String createdByUserId;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    public Budget() {
    }

    public List<Integer> thresholdList() {
        if (thresholds == null || thresholds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(thresholds.split(",")).map(String::trim).map(Integer::valueOf).toList();
    }

    public void setThresholdList(List<Integer> values) {
        this.thresholds = values.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    public String getBudgetId() {
        return budgetId;
    }

    public void setBudgetId(String budgetId) {
        this.budgetId = budgetId;
    }

    public BudgetScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(BudgetScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public String getScopeId() {
        return scopeId;
    }

    public void setScopeId(String scopeId) {
        this.scopeId = scopeId;
    }

    public BigDecimal getMonthlyAmount() {
        return monthlyAmount;
    }

    public void setMonthlyAmount(BigDecimal monthlyAmount) {
        this.monthlyAmount = monthlyAmount;
    }

    public String getThresholds() {
        return thresholds;
    }

    public Boolean getForecastAlert() {
        return forecastAlert;
    }

    public void setForecastAlert(Boolean forecastAlert) {
        this.forecastAlert = forecastAlert;
    }

    public BudgetActionType getActionType() {
        return actionType;
    }

    public void setActionType(BudgetActionType actionType) {
        this.actionType = actionType;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getAwsBudgetName() {
        return awsBudgetName;
    }

    public void setAwsBudgetName(String awsBudgetName) {
        this.awsBudgetName = awsBudgetName;
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

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }
}
