package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * A budget alert already fired (E20-T01/T02): one per budget, month, threshold and kind, so a
 * re-run of the evaluation never alerts twice.
 */
@Entity
@Table(name = "budget_alert")
public class BudgetAlert {

    /** ACTUAL: spend crossed a threshold; FORECAST: the month-end forecast exceeds the budget. */
    public enum Kind { ACTUAL, FORECAST }

    @Id
    @Column(name = "alert_id", length = 36)
    private String alertId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "budget_id", nullable = false)
    private Budget budget;

    /** "yyyy-MM". */
    @Column(name = "budget_month", nullable = false, length = 7, columnDefinition = "CHAR(7)")
    private String budgetMonth;

    @Column(name = "threshold", nullable = false)
    private Integer threshold;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private Kind kind;

    @Column(name = "spend", precision = 12, scale = 2)
    private BigDecimal spend;

    @Column(name = "forecast", precision = 12, scale = 2)
    private BigDecimal forecast;

    @Column(name = "fired_at", nullable = false)
    @CreationTimestamp
    private Timestamp firedAt;

    public BudgetAlert() {
    }

    public String getAlertId() {
        return alertId;
    }

    public void setAlertId(String alertId) {
        this.alertId = alertId;
    }

    public Budget getBudget() {
        return budget;
    }

    public void setBudget(Budget budget) {
        this.budget = budget;
    }

    public String getBudgetMonth() {
        return budgetMonth;
    }

    public void setBudgetMonth(String budgetMonth) {
        this.budgetMonth = budgetMonth;
    }

    public Integer getThreshold() {
        return threshold;
    }

    public void setThreshold(Integer threshold) {
        this.threshold = threshold;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public BigDecimal getSpend() {
        return spend;
    }

    public void setSpend(BigDecimal spend) {
        this.spend = spend;
    }

    public BigDecimal getForecast() {
        return forecast;
    }

    public void setForecast(BigDecimal forecast) {
        this.forecast = forecast;
    }

    public Timestamp getFiredAt() {
        return firedAt;
    }
}
