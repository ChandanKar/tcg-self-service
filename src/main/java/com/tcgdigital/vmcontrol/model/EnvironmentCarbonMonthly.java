package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * A month of carbon emissions attributed to one environment by cost share (E37-T02), in kg CO2e;
 * a row without an environment is the month's unallocated remainder.
 */
@Entity
@Table(name = "environment_carbon_monthly")
public class EnvironmentCarbonMonthly {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** Null for the unallocated remainder. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id")
    private Environment environment;

    @Column(name = "month", nullable = false)
    private LocalDate month;

    @Column(name = "emissions_mbm_kg", precision = 14, scale = 4)
    private BigDecimal emissionsMbmKg;

    @Column(name = "emissions_lbm_kg", precision = 14, scale = 4)
    private BigDecimal emissionsLbmKg;

    /** The cost the share was computed from (USD, the month). */
    @Column(name = "cost_basis", precision = 14, scale = 2)
    private BigDecimal costBasis;

    /** Attributed emissions as a share of the known accounts' emissions that month, in percent. */
    @Column(name = "coverage_pct", precision = 5, scale = 2)
    private BigDecimal coveragePct;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Environment getEnvironment() { return environment; }
    public void setEnvironment(Environment environment) { this.environment = environment; }
    public LocalDate getMonth() { return month; }
    public void setMonth(LocalDate month) { this.month = month; }
    public BigDecimal getEmissionsMbmKg() { return emissionsMbmKg; }
    public void setEmissionsMbmKg(BigDecimal emissionsMbmKg) { this.emissionsMbmKg = emissionsMbmKg; }
    public BigDecimal getEmissionsLbmKg() { return emissionsLbmKg; }
    public void setEmissionsLbmKg(BigDecimal emissionsLbmKg) { this.emissionsLbmKg = emissionsLbmKg; }
    public BigDecimal getCostBasis() { return costBasis; }
    public void setCostBasis(BigDecimal costBasis) { this.costBasis = costBasis; }
    public BigDecimal getCoveragePct() { return coveragePct; }
    public void setCoveragePct(BigDecimal coveragePct) { this.coveragePct = coveragePct; }
    public Timestamp getCreatedAt() { return createdAt; }
}
