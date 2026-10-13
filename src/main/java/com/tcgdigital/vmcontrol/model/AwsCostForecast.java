package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;

/** One point (or the total) of AWS's cost forecast for platform-managed resources (E35-T01). */
@Entity
@Table(name = "aws_cost_forecast")
public class AwsCostForecast {

    public static final String DAILY = "DAILY";
    public static final String MONTHLY = "MONTHLY";
    public static final String MANAGED = "MANAGED";

    @Id
    @Column(name = "forecast_id", length = 36)
    private String forecastId;

    @Column(name = "generated_on", nullable = false)
    private LocalDate generatedOn;

    @Column(name = "granularity", nullable = false, length = 10)
    private String granularity;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    /** Exclusive, as Cost Explorer reports it. */
    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "mean", precision = 14, scale = 2)
    private BigDecimal mean;

    @Column(name = "lower_bound", precision = 14, scale = 2)
    private BigDecimal lowerBound;

    @Column(name = "upper_bound", precision = 14, scale = 2)
    private BigDecimal upperBound;

    @Column(name = "scope", nullable = false, length = 20)
    private String scope = MANAGED;

    @Column(name = "is_total", nullable = false)
    private boolean total;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    public String getForecastId() { return forecastId; }
    public void setForecastId(String forecastId) { this.forecastId = forecastId; }
    public LocalDate getGeneratedOn() { return generatedOn; }
    public void setGeneratedOn(LocalDate generatedOn) { this.generatedOn = generatedOn; }
    public String getGranularity() { return granularity; }
    public void setGranularity(String granularity) { this.granularity = granularity; }
    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }
    public BigDecimal getMean() { return mean; }
    public void setMean(BigDecimal mean) { this.mean = mean; }
    public BigDecimal getLowerBound() { return lowerBound; }
    public void setLowerBound(BigDecimal lowerBound) { this.lowerBound = lowerBound; }
    public BigDecimal getUpperBound() { return upperBound; }
    public void setUpperBound(BigDecimal upperBound) { this.upperBound = upperBound; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    public boolean isTotal() { return total; }
    public void setTotal(boolean total) { this.total = total; }
    public Timestamp getCreatedAt() { return createdAt; }
}
