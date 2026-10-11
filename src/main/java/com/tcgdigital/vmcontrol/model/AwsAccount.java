package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * An AWS account the platform reaches (E28-T03). The default account is the identity behind the
 * app's own credentials; member accounts are reached by assuming {@code roleArn} (E28-T04).
 * {@code regions} is a comma-separated list; empty means "the app's default regions".
 */
@Entity
@Table(name = "aws_account")
public class AwsAccount {

    /** The default account's fixed ref (created by V70). */
    public static final String DEFAULT_REF = "00000000-0000-0000-0000-000000000001";

    public enum Source { DEFAULT, MANUAL, ORGANIZATIONS }

    @Id
    @Column(name = "aws_account_ref", length = 36)
    private String awsAccountRef;

    @Column(name = "account_number", length = 12, unique = true)
    private String accountNumber;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "role_arn", length = 2048)
    private String roleArn;

    @Column(name = "external_id", length = 1224)
    private String externalId;

    @Column(name = "regions", columnDefinition = "TEXT")
    private String regions;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "is_billing_account", nullable = false)
    private boolean isBillingAccount;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private Source source = Source.MANUAL;

    @Column(name = "last_verified_at")
    private Timestamp lastVerifiedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Timestamp createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Timestamp updatedAt;

    @PrePersist
    void assignRef() {
        if (awsAccountRef == null) {
            awsAccountRef = UUID.randomUUID().toString();
        }
    }

    /** The configured regions, trimmed; empty when none are set. */
    public List<String> getRegionList() {
        if (regions == null || regions.isBlank()) {
            return List.of();
        }
        return Arrays.stream(regions.split(",")).map(String::trim).filter(r -> !r.isEmpty()).toList();
    }

    public String getAwsAccountRef() { return awsAccountRef; }
    public void setAwsAccountRef(String awsAccountRef) { this.awsAccountRef = awsAccountRef; }
    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public String getRegions() { return regions; }
    public void setRegions(String regions) { this.regions = regions; }
    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }
    public boolean isBillingAccount() { return isBillingAccount; }
    public void setBillingAccount(boolean billingAccount) { this.isBillingAccount = billingAccount; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { this.isActive = active; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public Timestamp getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(Timestamp lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Timestamp getCreatedAt() { return createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
}
