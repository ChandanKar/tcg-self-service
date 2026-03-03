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

    @Column(name = "azure_ad_object_id", nullable = true, unique = true)
    private String azureAdObjectId;

    @Column(name = "username", length = 150, unique = true)
    private String username;

    @Column(name = "password", length = 300)
    private String password;

    @Column(name = "company_name", length = 300)
    private String companyName;

    @Column(name = "legacy_user_id")
    private Integer legacyUserId;

    @Column(name = "password_updated_at")
    private Timestamp passwordUpdatedAt;

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

    /**
     * Factory method to create a new user from username/password.
     */
    public static User fromUsernamePassword(String username, String password, String email,
                                            String displayName, String companyName) {
        User user = new User();
        user.setUserId(java.util.UUID.randomUUID().toString());
        user.setUsername(username);
        user.setPassword(password);  // Plain text (alpha); hash later
        user.setEmail(email);
        user.setDisplayName(displayName);
        user.setCompanyName(companyName);
        user.setPasswordUpdatedAt(new Timestamp(System.currentTimeMillis()));
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public Integer getLegacyUserId() {
        return legacyUserId;
    }

    public void setLegacyUserId(Integer legacyUserId) {
        this.legacyUserId = legacyUserId;
    }

    public Timestamp getPasswordUpdatedAt() {
        return passwordUpdatedAt;
    }

    public void setPasswordUpdatedAt(Timestamp passwordUpdatedAt) {
        this.passwordUpdatedAt = passwordUpdatedAt;
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
                ", username='" + username + '\'' +
                ", companyName='" + companyName + '\'' +
                ", admin=" + admin +
                ", envAdmin=" + envAdmin +
                ", isActive=" + isActive +
                '}';
    }
}

