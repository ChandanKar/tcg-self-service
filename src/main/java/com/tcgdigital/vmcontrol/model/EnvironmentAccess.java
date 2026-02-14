package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.sql.Timestamp;

/**
 * EnvironmentAccess - maps user access to specific environments.
 * Represents the access grant after an access request is approved.
 */
@Entity
@Table(name = "environment_access")
public class EnvironmentAccess {

    @Id
    @Column(name = "access_id", length = 36)
    private String accessId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false, length = 20)
    private AccessLevel accessLevel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "granted_by_user_id", nullable = false)
    private User grantedBy;

    @Column(name = "granted_at", nullable = false)
    @CreationTimestamp
    private Timestamp grantedAt;

    @Column(name = "expires_at")
    private Timestamp expiresAt;

    @Column(name = "revoked_at")
    private Timestamp revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccessStatus status = AccessStatus.ACTIVE;

    @Column(columnDefinition = "TEXT")
    private String notes;

    public EnvironmentAccess() {
    }

    public EnvironmentAccess(String accessId) {
        this.accessId = accessId;
    }

    /**
     * Factory method to create a new environment access grant.
     */
    public static EnvironmentAccess create(Environment environment, User user,
                                           AccessLevel accessLevel, User grantedBy) {
        EnvironmentAccess access = new EnvironmentAccess();
        access.setAccessId(java.util.UUID.randomUUID().toString());
        access.setEnvironment(environment);
        access.setUser(user);
        access.setAccessLevel(accessLevel);
        access.setGrantedBy(grantedBy);
        access.setStatus(AccessStatus.ACTIVE);
        return access;
    }

    /**
     * Revoke this access.
     */
    public void revoke() {
        this.status = AccessStatus.REVOKED;
        this.revokedAt = new Timestamp(System.currentTimeMillis());
    }

    /**
     * Check if this access is currently active.
     */
    public boolean isActive() {
        if (status != AccessStatus.ACTIVE) {
            return false;
        }
        if (expiresAt != null && expiresAt.before(new Timestamp(System.currentTimeMillis()))) {
            return false;
        }
        return true;
    }

    // Getters and Setters
    public String getAccessId() {
        return accessId;
    }

    public void setAccessId(String accessId) {
        this.accessId = accessId;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.accessLevel = accessLevel;
    }

    public User getGrantedBy() {
        return grantedBy;
    }

    public void setGrantedBy(User grantedBy) {
        this.grantedBy = grantedBy;
    }

    public Timestamp getGrantedAt() {
        return grantedAt;
    }

    public void setGrantedAt(Timestamp grantedAt) {
        this.grantedAt = grantedAt;
    }

    public Timestamp getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Timestamp expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Timestamp getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Timestamp revokedAt) {
        this.revokedAt = revokedAt;
    }

    public AccessStatus getStatus() {
        return status;
    }

    public void setStatus(AccessStatus status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    @Override
    public String toString() {
        return "EnvironmentAccess{" +
                "accessId='" + accessId + '\'' +
                ", environmentId='" + (environment != null ? environment.getEnvironmentId() : null) + '\'' +
                ", userId='" + (user != null ? user.getUserId() : null) + '\'' +
                ", accessLevel=" + accessLevel +
                ", status=" + status +
                '}';
    }
}

