package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AWS Compute Optimizer's latest view of one EC2 instance (E30-T04), captured daily: the finding
 * (OVER_PROVISIONED, UNDER_PROVISIONED, OPTIMIZED, NOT_OPTIMIZED), why, and every option in rank
 * order. {@code vmId} links a registered VM; null for an instance the platform does not manage.
 */
@Entity
@Table(name = "compute_optimizer_recommendation")
public class ComputeOptimizerRecommendation {

    @Id
    @Column(name = "recommendation_id", length = 36)
    private String recommendationId;

    @Column(name = "vm_id", length = 36)
    private String vmId;

    @Column(name = "provider_vm_id", nullable = false, length = 64)
    private String providerVmId;

    @Column(name = "aws_account_ref", length = 36)
    private String awsAccountRef;

    @Column(name = "region", nullable = false, length = 32)
    private String region;

    @Column(name = "finding", nullable = false, length = 40)
    private String finding;

    /** Comma-separated reason codes, e.g. CPUOverprovisioned,MemoryOverprovisioned. */
    @Column(name = "finding_reasons", columnDefinition = "TEXT")
    private String findingReasons;

    @Column(name = "current_instance_type", length = 64)
    private String currentInstanceType;

    @Column(name = "lookback_days")
    private Integer lookbackDays;

    /** Compute Optimizer saw memory (CloudWatch agent), so its options account for it. */
    @Column(name = "memory_metric_present", nullable = false)
    private boolean memoryMetricPresent;

    @Column(name = "captured_at", nullable = false)
    private Timestamp capturedAt;

    @OneToMany(mappedBy = "recommendation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("optionRank ASC")
    private List<ComputeOptimizerOption> options = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        if (recommendationId == null) recommendationId = UUID.randomUUID().toString();
    }

    public void addOption(ComputeOptimizerOption option) {
        option.setRecommendation(this);
        options.add(option);
    }

    public String getRecommendationId() { return recommendationId; }
    public void setRecommendationId(String recommendationId) { this.recommendationId = recommendationId; }
    public String getVmId() { return vmId; }
    public void setVmId(String vmId) { this.vmId = vmId; }
    public String getProviderVmId() { return providerVmId; }
    public void setProviderVmId(String providerVmId) { this.providerVmId = providerVmId; }
    public String getAwsAccountRef() { return awsAccountRef; }
    public void setAwsAccountRef(String awsAccountRef) { this.awsAccountRef = awsAccountRef; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getFinding() { return finding; }
    public void setFinding(String finding) { this.finding = finding; }
    public String getFindingReasons() { return findingReasons; }
    public void setFindingReasons(String findingReasons) { this.findingReasons = findingReasons; }
    public String getCurrentInstanceType() { return currentInstanceType; }
    public void setCurrentInstanceType(String currentInstanceType) { this.currentInstanceType = currentInstanceType; }
    public Integer getLookbackDays() { return lookbackDays; }
    public void setLookbackDays(Integer lookbackDays) { this.lookbackDays = lookbackDays; }
    public boolean isMemoryMetricPresent() { return memoryMetricPresent; }
    public void setMemoryMetricPresent(boolean memoryMetricPresent) { this.memoryMetricPresent = memoryMetricPresent; }
    public Timestamp getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Timestamp capturedAt) { this.capturedAt = capturedAt; }
    public List<ComputeOptimizerOption> getOptions() { return options; }
}
