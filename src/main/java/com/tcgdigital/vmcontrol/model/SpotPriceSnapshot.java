package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.UUID;

/** The newest Spot price of an instance type in one availability zone on one day (E31-T02). */
@Entity
@Table(name = "spot_price_snapshot")
public class SpotPriceSnapshot {

    @Id
    @Column(name = "snapshot_id", length = 36)
    private String snapshotId;

    @Column(name = "aws_account_ref", length = 36)
    private String awsAccountRef;

    @Column(name = "region", nullable = false, length = 32)
    private String region;

    @Column(name = "availability_zone", nullable = false, length = 32)
    private String availabilityZone;

    @Column(name = "instance_type", nullable = false, length = 64)
    private String instanceType;

    @Column(name = "price_per_hour", nullable = false, precision = 12, scale = 6)
    private BigDecimal pricePerHour;

    @Column(name = "captured_on", nullable = false)
    private Date capturedOn;

    @PrePersist
    protected void onCreate() {
        if (snapshotId == null) snapshotId = UUID.randomUUID().toString();
    }

    public String getSnapshotId() { return snapshotId; }
    public String getAwsAccountRef() { return awsAccountRef; }
    public void setAwsAccountRef(String awsAccountRef) { this.awsAccountRef = awsAccountRef; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String availabilityZone) { this.availabilityZone = availabilityZone; }
    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String instanceType) { this.instanceType = instanceType; }
    public BigDecimal getPricePerHour() { return pricePerHour; }
    public void setPricePerHour(BigDecimal pricePerHour) { this.pricePerHour = pricePerHour; }
    public Date getCapturedOn() { return capturedOn; }
    public void setCapturedOn(Date capturedOn) { this.capturedOn = capturedOn; }
}
