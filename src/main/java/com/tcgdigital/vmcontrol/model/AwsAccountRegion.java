package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.UUID;

/**
 * A region an AWS account has enabled (U19-T01), as ec2:DescribeRegions last reported it, and
 * whether the app's calls there were refused recently (then the region is skipped for a day).
 */
@Entity
@Table(name = "aws_account_region")
public class AwsAccountRegion {

    @Id
    @Column(name = "aws_account_region_id", length = 36)
    private String awsAccountRegionId;

    @Column(name = "aws_account_ref", nullable = false, length = 36)
    private String awsAccountRef;

    @Column(name = "region_code", nullable = false, length = 32)
    private String regionCode;

    @Column(name = "opt_in_status", length = 32)
    private String optInStatus;

    @Column(name = "read_at", nullable = false)
    private Timestamp readAt;

    @Column(name = "refused_at")
    private Timestamp refusedAt;

    @Column(name = "refused_reason", length = 500)
    private String refusedReason;

    public AwsAccountRegion() {
    }

    public AwsAccountRegion(String awsAccountRef, String regionCode) {
        this.awsAccountRef = awsAccountRef;
        this.regionCode = regionCode;
    }

    @PrePersist
    protected void onCreate() {
        if (awsAccountRegionId == null) awsAccountRegionId = UUID.randomUUID().toString();
    }

    public String getAwsAccountRegionId() { return awsAccountRegionId; }
    public String getAwsAccountRef() { return awsAccountRef; }
    public void setAwsAccountRef(String awsAccountRef) { this.awsAccountRef = awsAccountRef; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
    public String getOptInStatus() { return optInStatus; }
    public void setOptInStatus(String optInStatus) { this.optInStatus = optInStatus; }
    public Timestamp getReadAt() { return readAt; }
    public void setReadAt(Timestamp readAt) { this.readAt = readAt; }
    public Timestamp getRefusedAt() { return refusedAt; }
    public void setRefusedAt(Timestamp refusedAt) { this.refusedAt = refusedAt; }
    public String getRefusedReason() { return refusedReason; }
    public void setRefusedReason(String refusedReason) { this.refusedReason = refusedReason; }
}
