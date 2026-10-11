package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** One piece of account-level waste, per type, region and AWS resource (E22-T01). */
@Entity
@Table(name = "waste_finding")
public class WasteFinding {

    @Id
    @Column(name = "finding_id", length = 36)
    private String findingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false, length = 30)
    private WasteFindingType findingType;

    @Column(name = "region", nullable = false, length = 30)
    private String region;

    @Column(name = "resource_id", nullable = false, length = 255)
    private String resourceId;

    @Column(name = "resource_name", length = 255)
    private String resourceName;

    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(name = "vm_id", length = 36)
    private String vmId;

    /** JSON with type-specific facts (size, volume type, current cost...). */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    /** Estimated monthly cost (or, for gp2, the saving), USD. */
    @Column(name = "est_monthly_cost", precision = 12, scale = 2)
    private BigDecimal estMonthlyCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WasteFindingStatus status = WasteFindingStatus.OPEN;

    @Column(name = "first_seen_at", nullable = false)
    private Timestamp firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Timestamp lastSeenAt;

    /** Waste at least since then (a lower bound, e.g. unattached at least since). */
    @Column(name = "eligible_since")
    private Timestamp eligibleSince;

    @Column(name = "dismissed_by", length = 36)
    private String dismissedBy;

    @Column(name = "dismissed_reason", length = 500)
    private String dismissedReason;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    public WasteFinding() {
    }

    public String getFindingId() { return findingId; }
    public void setFindingId(String findingId) { this.findingId = findingId; }
    public WasteFindingType getFindingType() { return findingType; }
    public void setFindingType(WasteFindingType findingType) { this.findingType = findingType; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }
    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }
    public String getVmId() { return vmId; }
    public void setVmId(String vmId) { this.vmId = vmId; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public BigDecimal getEstMonthlyCost() { return estMonthlyCost; }
    public void setEstMonthlyCost(BigDecimal estMonthlyCost) { this.estMonthlyCost = estMonthlyCost; }
    public WasteFindingStatus getStatus() { return status; }
    public void setStatus(WasteFindingStatus status) { this.status = status; }
    public Timestamp getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(Timestamp firstSeenAt) { this.firstSeenAt = firstSeenAt; }
    public Timestamp getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Timestamp lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Timestamp getEligibleSince() { return eligibleSince; }
    public void setEligibleSince(Timestamp eligibleSince) { this.eligibleSince = eligibleSince; }
    public String getDismissedBy() { return dismissedBy; }
    public void setDismissedBy(String dismissedBy) { this.dismissedBy = dismissedBy; }
    public String getDismissedReason() { return dismissedReason; }
    public void setDismissedReason(String dismissedReason) { this.dismissedReason = dismissedReason; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
}
