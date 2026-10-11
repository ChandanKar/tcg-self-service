package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * When a start or runtime extension needs approval (E25-T03). Global when neither environment nor
 * team is set. Any one condition matching triggers it.
 */
@Entity
@Table(name = "approval_policy")
public class ApprovalPolicy {

    public enum ApproverMode { ANY, ALL }

    public enum ApproverType { USER, TEAM, ENV_ADMINS }

    /** Who approves: a user, a team's members, or the environment's admins (approverId null). */
    @Embeddable
    public static class Approver {
        @Enumerated(EnumType.STRING)
        @Column(name = "approver_type", nullable = false, length = 20)
        private ApproverType type;

        @Column(name = "approver_id", length = 36)
        private String approverId;

        public Approver() {
        }

        public Approver(ApproverType type, String approverId) {
            this.type = type;
            this.approverId = approverId;
        }

        public ApproverType getType() { return type; }
        public String getApproverId() { return approverId; }
    }

    @Id
    @Column(name = "policy_id", length = 36)
    private String policyId;

    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(name = "team_id", length = 36)
    private String teamId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Comma-separated globs, e.g. "*.8xlarge,p*". */
    @Column(name = "instance_type_patterns", columnDefinition = "TEXT")
    private String instanceTypePatterns;

    @Column(name = "min_vcpu")
    private Integer minVcpu;

    @Column(name = "max_runtime_hours")
    private Integer maxRuntimeHours;

    @Column(name = "max_estimated_cost", precision = 12, scale = 2)
    private BigDecimal maxEstimatedCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "approver_mode", nullable = false, length = 5)
    private ApproverMode approverMode = ApproverMode.ANY;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "approval_policy_approver", joinColumns = @JoinColumn(name = "policy_id"))
    @OrderColumn(name = "position")
    private List<Approver> approvers = new ArrayList<>();

    public ApprovalPolicy() {
    }

    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInstanceTypePatterns() { return instanceTypePatterns; }
    public void setInstanceTypePatterns(String instanceTypePatterns) { this.instanceTypePatterns = instanceTypePatterns; }
    public Integer getMinVcpu() { return minVcpu; }
    public void setMinVcpu(Integer minVcpu) { this.minVcpu = minVcpu; }
    public Integer getMaxRuntimeHours() { return maxRuntimeHours; }
    public void setMaxRuntimeHours(Integer maxRuntimeHours) { this.maxRuntimeHours = maxRuntimeHours; }
    public BigDecimal getMaxEstimatedCost() { return maxEstimatedCost; }
    public void setMaxEstimatedCost(BigDecimal maxEstimatedCost) { this.maxEstimatedCost = maxEstimatedCost; }
    public ApproverMode getApproverMode() { return approverMode; }
    public void setApproverMode(ApproverMode approverMode) { this.approverMode = approverMode; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public List<Approver> getApprovers() { return approvers; }

    /** Replaces the approvers in place (the collection is managed by Hibernate). */
    public void setApprovers(List<Approver> approvers) {
        this.approvers.clear();
        this.approvers.addAll(approvers);
    }
}
