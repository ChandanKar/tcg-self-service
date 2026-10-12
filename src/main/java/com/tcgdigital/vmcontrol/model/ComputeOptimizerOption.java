package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One option of a Compute Optimizer recommendation (E30-T04): the type, AWS's performance risk
 * (0 very low .. 4 very high), migration effort, platform differences (e.g. Architecture when the
 * option is Graviton) and AWS's own monthly saving estimate.
 */
@Entity
@Table(name = "compute_optimizer_option")
public class ComputeOptimizerOption {

    @Id
    @Column(name = "option_id", length = 36)
    private String optionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recommendation_id", nullable = false)
    private ComputeOptimizerRecommendation recommendation;

    @Column(name = "option_rank", nullable = false)
    private Integer optionRank;

    @Column(name = "instance_type", nullable = false, length = 64)
    private String instanceType;

    @Column(name = "performance_risk", precision = 4, scale = 2)
    private BigDecimal performanceRisk;

    @Column(name = "migration_effort", length = 20)
    private String migrationEffort;

    /** Comma-separated, e.g. Architecture,Hypervisor. */
    @Column(name = "platform_differences", columnDefinition = "TEXT")
    private String platformDifferences;

    @Column(name = "estimated_monthly_savings", precision = 12, scale = 2)
    private BigDecimal estimatedMonthlySavings;

    @PrePersist
    protected void onCreate() {
        if (optionId == null) optionId = UUID.randomUUID().toString();
    }

    public String getOptionId() { return optionId; }
    public void setOptionId(String optionId) { this.optionId = optionId; }
    public ComputeOptimizerRecommendation getRecommendation() { return recommendation; }
    public void setRecommendation(ComputeOptimizerRecommendation recommendation) { this.recommendation = recommendation; }
    public Integer getOptionRank() { return optionRank; }
    public void setOptionRank(Integer optionRank) { this.optionRank = optionRank; }
    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }
    public BigDecimal getPerformanceRisk() { return performanceRisk; }
    public void setPerformanceRisk(BigDecimal performanceRisk) { this.performanceRisk = performanceRisk; }
    public String getMigrationEffort() { return migrationEffort; }
    public void setMigrationEffort(String migrationEffort) { this.migrationEffort = migrationEffort; }
    public String getPlatformDifferences() { return platformDifferences; }
    public void setPlatformDifferences(String platformDifferences) { this.platformDifferences = platformDifferences; }
    public BigDecimal getEstimatedMonthlySavings() { return estimatedMonthlySavings; }
    public void setEstimatedMonthlySavings(BigDecimal estimatedMonthlySavings) { this.estimatedMonthlySavings = estimatedMonthlySavings; }
}
