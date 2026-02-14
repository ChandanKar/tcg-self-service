package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.User;

import java.sql.Timestamp;

/**
 * DTO for User entity.
 */
public class UserDTO {

    private String userId;
    private String email;
    private String displayName;
    private boolean admin;
    private boolean envAdmin;
    private boolean active;
    private Timestamp createdAt;
    private Timestamp lastLoginAt;

    public UserDTO() {
    }

    /**
     * Create DTO from User entity.
     */
    public static UserDTO fromEntity(User user) {
        UserDTO dto = new UserDTO();
        dto.setUserId(user.getUserId());
        dto.setEmail(user.getEmail());
        dto.setDisplayName(user.getDisplayName());
        dto.setAdmin(user.isAdmin());
        dto.setEnvAdmin(user.isEnvAdmin());
        dto.setActive(user.isActive());
        dto.setCreatedAt(user.getCreatedAt());
        dto.setLastLoginAt(user.getLastLoginAt());
        return dto;
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

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public boolean isEnvAdmin() {
        return envAdmin;
    }

    public void setEnvAdmin(boolean envAdmin) {
        this.envAdmin = envAdmin;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Timestamp lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }
}

