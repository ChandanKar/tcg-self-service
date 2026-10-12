package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * An approved environment template (E32-T01): what provisions it ({@code engine} and its
 * {@code engineRef} JSON), the parameters a requester fills in ({@code parameterSchema} JSON),
 * where it lands, its lifetime limits, and who may request and approve it.
 */
@Entity
@Table(name = "environment_template")
public class EnvironmentTemplate {

    @Id
    @Column(name = "template_id", length = 36)
    private String templateId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "engine", nullable = false, length = 20)
    private ProvisioningEngineType engine;

    /** Service Catalog: productId, provisioningArtifactId, launchPathId. Terraform: organization, workspacePrefix, module. */
    @Column(name = "engine_ref", nullable = false, columnDefinition = "TEXT")
    private String engineRef;

    /** JSON array of {key, label, type: string|number|enum, required, default, options, pattern}. */
    @Column(name = "parameter_schema", columnDefinition = "TEXT")
    private String parameterSchema;

    @Column(name = "aws_account_ref", length = 36)
    private String awsAccountRef;

    @Column(name = "region", length = 32)
    private String region;

    @Column(name = "default_ttl_hours", nullable = false)
    private Integer defaultTtlHours = 72;

    @Column(name = "max_ttl_hours", nullable = false)
    private Integer maxTtlHours = 336;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval = true;

    @Column(name = "approver_role", nullable = false, length = 20)
    private String approverRole = "ADMIN";

    /** Comma-separated roles that may request it: ENV_ADMIN, USER (admins always may). */
    @Column(name = "allowed_roles", nullable = false, length = 100)
    private String allowedRoles = "ENV_ADMIN,USER";

    @Column(name = "estimated_monthly_cost", precision = 12, scale = 2)
    private BigDecimal estimatedMonthlyCost;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @PrePersist
    protected void onCreate() {
        if (templateId == null) templateId = UUID.randomUUID().toString();
        if (createdAt == null) createdAt = new Timestamp(System.currentTimeMillis());
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = new Timestamp(System.currentTimeMillis());
    }

    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public ProvisioningEngineType getEngine() { return engine; }
    public void setEngine(ProvisioningEngineType engine) { this.engine = engine; }
    public String getEngineRef() { return engineRef; }
    public void setEngineRef(String engineRef) { this.engineRef = engineRef; }
    public String getParameterSchema() { return parameterSchema; }
    public void setParameterSchema(String parameterSchema) { this.parameterSchema = parameterSchema; }
    public String getAwsAccountRef() { return awsAccountRef; }
    public void setAwsAccountRef(String awsAccountRef) { this.awsAccountRef = awsAccountRef; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public Integer getDefaultTtlHours() { return defaultTtlHours; }
    public void setDefaultTtlHours(Integer defaultTtlHours) { this.defaultTtlHours = defaultTtlHours; }
    public Integer getMaxTtlHours() { return maxTtlHours; }
    public void setMaxTtlHours(Integer maxTtlHours) { this.maxTtlHours = maxTtlHours; }
    public boolean isRequiresApproval() { return requiresApproval; }
    public void setRequiresApproval(boolean requiresApproval) { this.requiresApproval = requiresApproval; }
    public String getApproverRole() { return approverRole; }
    public void setApproverRole(String approverRole) { this.approverRole = approverRole; }
    public String getAllowedRoles() { return allowedRoles; }
    public void setAllowedRoles(String allowedRoles) { this.allowedRoles = allowedRoles; }
    public BigDecimal getEstimatedMonthlyCost() { return estimatedMonthlyCost; }
    public void setEstimatedMonthlyCost(BigDecimal estimatedMonthlyCost) { this.estimatedMonthlyCost = estimatedMonthlyCost; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
}
