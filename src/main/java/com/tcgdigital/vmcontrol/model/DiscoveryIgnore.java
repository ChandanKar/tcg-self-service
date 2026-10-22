package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.UUID;

/** An EC2 instance or EKS cluster an administrator chose to ignore in "Found in AWS" (U19-T03). */
@Entity
@Table(name = "discovery_ignore")
public class DiscoveryIgnore {

    /** EC2 (an instance id) or EKS (a cluster name). */
    public enum Kind { EC2, EKS }

    @Id
    @Column(name = "discovery_ignore_id", length = 36)
    private String discoveryIgnoreId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_kind", nullable = false, length = 16)
    private Kind resourceKind;

    @Column(name = "aws_account_ref", nullable = false, length = 36)
    private String awsAccountRef;

    @Column(name = "region_code", nullable = false, length = 32)
    private String regionCode;

    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @Column(name = "resource_name")
    private String resourceName;

    @Column(name = "ignored_by", length = 36)
    private String ignoredBy;

    @Column(name = "ignored_at", nullable = false)
    private Timestamp ignoredAt;

    @PrePersist
    protected void onCreate() {
        if (discoveryIgnoreId == null) discoveryIgnoreId = UUID.randomUUID().toString();
        if (ignoredAt == null) ignoredAt = new Timestamp(System.currentTimeMillis());
    }

    public String getDiscoveryIgnoreId() { return discoveryIgnoreId; }
    public Kind getResourceKind() { return resourceKind; }
    public void setResourceKind(Kind resourceKind) { this.resourceKind = resourceKind; }
    public String getAwsAccountRef() { return awsAccountRef; }
    public void setAwsAccountRef(String awsAccountRef) { this.awsAccountRef = awsAccountRef; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }
    public String getIgnoredBy() { return ignoredBy; }
    public void setIgnoredBy(String ignoredBy) { this.ignoredBy = ignoredBy; }
    public Timestamp getIgnoredAt() { return ignoredAt; }

    /** kind|account|region|id: the key the inbox matches on. */
    public String key() {
        return key(resourceKind, awsAccountRef, regionCode, resourceId);
    }

    public static String key(Kind kind, String accountRef, String region, String resourceId) {
        return kind + "|" + accountRef + "|" + region + "|" + resourceId;
    }
}
