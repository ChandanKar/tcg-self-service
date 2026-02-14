package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;

/**
 * User entity - represents an application user registered via Azure AD.
 * Maps to the 'app_user' table (using 'user' is reserved in H2).
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "azure_ad_object_id", nullable = false, unique = true)
    private String azureAdObjectId;

    @Column(nullable = false)
    private Boolean admin = false;

    @Column(name = "env_admin", nullable = false)
    private Boolean envAdmin = false;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    @Column(name = "last_login_at")
    private Timestamp lastLoginAt;

    public User() {
    }

    public User(String userId) {
        this.userId = userId;
    }

    /**
     * Factory method to create a new user from Azure AD claims.
     */
    public static User fromAzureAd(String azureAdObjectId, String email, String displayName) {
        User user = new User();
        user.setUserId(java.util.UUID.randomUUID().toString());
        user.setAzureAdObjectId(azureAdObjectId);
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setAdmin(false);
        user.setEnvAdmin(false);
        user.setIsActive(true);
        return user;
    }

    // Getters and Setters
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAzureAdObjectId() {
        return azureAdObjectId;
    }

    public void setAzureAdObjectId(String azureAdObjectId) {
        this.azureAdObjectId = azureAdObjectId;
    }

    public Boolean getAdmin() {
        return admin;
    }

    public void setAdmin(Boolean admin) {
        this.admin = admin;
    }

    public Boolean getEnvAdmin() {
        return envAdmin;
    }

    public void setEnvAdmin(Boolean envAdmin) {
        this.envAdmin = envAdmin;
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

    public Timestamp getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Timestamp lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    /**
     * Check if user has admin role.
     */
    public boolean isAdmin() {
        return Boolean.TRUE.equals(admin);
    }

    /**
     * Check if user has environment admin role.
     */
    public boolean isEnvAdmin() {
        return Boolean.TRUE.equals(envAdmin);
    }

    /**
     * Check if user is active.
     */
    public boolean isActive() {
        return Boolean.TRUE.equals(isActive);
    }

    @Override
    public String toString() {
        return "User{" +
                "userId='" + userId + '\'' +
                ", email='" + email + '\'' +
                ", displayName='" + displayName + '\'' +
                ", admin=" + admin +
                ", envAdmin=" + envAdmin +
                ", isActive=" + isActive +
                '}';
    }
}

