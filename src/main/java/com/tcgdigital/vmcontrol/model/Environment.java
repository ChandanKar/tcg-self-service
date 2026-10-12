package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Environment - top level organizational unit for VM management.
 * Represents a logical grouping like "QA", "Dev", "Staging".
 */
@Entity
@Table(name = "environment")
public class Environment {

    @Id
    @Column(name = "environment_id", length = 36)
    private String environmentId;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    /** Starts are blocked until then by an approved budget freeze (E20-T03); null when not frozen. */
    @Column(name = "start_frozen_until")
    private java.sql.Timestamp startFrozenUntil;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    /**
     * The team that owns the environment (E23-T01); null when unassigned. Eager: the team is
     * read by scheduled jobs outside a transaction, and the team table is small.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "team_id")
    private Team team;

    @Column(name = "service_type", length = 20, nullable = false)
    private String serviceType = "EC2";

    /** Production environments are excluded from idle auto-stop (V35, E16). */
    @Column(name = "is_production", nullable = false)
    private Boolean isProduction = false;

    /** EKS only: the exact, case-sensitive cluster name used for AWS calls (V34). */
    @Column(name = "eks_cluster_name", length = 255)
    private String eksClusterName;

    /** The AWS account the environment lives in (E28-T03, V70); the default account when not chosen. */
    @Column(name = "aws_account_ref", length = 36)
    private String awsAccountRef;

    @PrePersist
    void defaultAwsAccount() {
        if (awsAccountRef == null) {
            awsAccountRef = AwsAccount.DEFAULT_REF;
        }
    }

    /** Environment leases (E15, V42): opted in per environment, on top of lease.enabled. */
    @Column(name = "lease_enabled", nullable = false)
    private Boolean leaseEnabled = false;

    /** Spot opt-in by the environment's administrators (E31-T03). */
    @Enumerated(EnumType.STRING)
    @Column(name = "spot_policy", nullable = false, length = 20)
    private SpotPolicy spotPolicy = SpotPolicy.NONE;

    public SpotPolicy getSpotPolicy() {
        return spotPolicy;
    }

    public void setSpotPolicy(SpotPolicy spotPolicy) {
        this.spotPolicy = spotPolicy == null ? SpotPolicy.NONE : spotPolicy;
    }

    /** Owner's default lease length in minutes; null = the platform default. */
    @Column(name = "lease_default_minutes")
    private Integer leaseDefaultMinutes;

    /** Owner's maximum lease length in minutes; null = the platform maximum. */
    @Column(name = "lease_max_minutes")
    private Integer leaseMaxMinutes;

    @OneToMany(mappedBy = "environment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<VmGroup> groups = new ArrayList<>();

    public Environment() {
    }

    public Environment(String environmentId) {
        this.environmentId = environmentId;
    }

    // Getters and Setters
    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getServiceType() {
        return serviceType;
    }

    public void setServiceType(String serviceType) {
        this.serviceType = serviceType;
    }

    public List<VmGroup> getGroups() {
        return groups;
    }

    public void setGroups(List<VmGroup> groups) {
        this.groups = groups;
    }

    public Boolean getLeaseEnabled() {
        return leaseEnabled;
    }

    public void setLeaseEnabled(Boolean leaseEnabled) {
        this.leaseEnabled = leaseEnabled;
    }

    public Integer getLeaseDefaultMinutes() {
        return leaseDefaultMinutes;
    }

    public void setLeaseDefaultMinutes(Integer leaseDefaultMinutes) {
        this.leaseDefaultMinutes = leaseDefaultMinutes;
    }

    public Integer getLeaseMaxMinutes() {
        return leaseMaxMinutes;
    }

    public void setLeaseMaxMinutes(Integer leaseMaxMinutes) {
        this.leaseMaxMinutes = leaseMaxMinutes;
    }

    public String getAwsAccountRef() {
        return awsAccountRef;
    }

    public void setAwsAccountRef(String awsAccountRef) {
        this.awsAccountRef = awsAccountRef;
    }

    public String getEksClusterName() {
        return eksClusterName;
    }

    public void setEksClusterName(String eksClusterName) {
        this.eksClusterName = eksClusterName;
    }

    /** The cluster name for EKS calls: the stored exact name, else (legacy rows) the name. */
    @Transient
    public String getEffectiveClusterName() {
        return eksClusterName != null && !eksClusterName.isBlank() ? eksClusterName : name;
    }

    public Boolean getIsProduction() {
        return isProduction;
    }

    public void setIsProduction(Boolean isProduction) {
        this.isProduction = isProduction;
    }

    public java.sql.Timestamp getStartFrozenUntil() {
        return startFrozenUntil;
    }

    public void setStartFrozenUntil(java.sql.Timestamp startFrozenUntil) {
        this.startFrozenUntil = startFrozenUntil;
    }

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
    }

    /** The owning team's id, or null. */
    public String getTeamId() {
        return team == null ? null : team.getTeamId();
    }
}
