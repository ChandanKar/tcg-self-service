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
    /** Onboarded from the admin panel and has not signed in yet. */
    private boolean pendingFirstLogin;
    /** Onboarded by manual entry — not checked against the Entra directory. */
    private boolean unverified;
    /** Username for password sign-in; null for Entra-only accounts. */
    private String username;
    /** A service account (E34-T03): no sign-in, tokens only. */
    private boolean serviceAccount;

    public boolean isServiceAccount() {
        return serviceAccount;
    }

    public void setServiceAccount(boolean serviceAccount) {
        this.serviceAccount = serviceAccount;
    }
    /** Whether a password is set (the hash itself is never exposed). */
    private boolean hasPassword;
    /**
     * Whether the person signs in with Microsoft (U05-T05): linked to Entra ID, or onboarded by hand
     * without a password (they are expected to sign in with Microsoft). Never for service accounts.
     */
    private boolean signsInWithMicrosoft;
    /** MICROSOFT, PASSWORD, MICROSOFT_AND_PASSWORD, or NONE for service accounts (they use API tokens). */
    private String signInMethod;
    /** U13-T01 */
    private String department;
    private String projectCode;

    public String getDepartment() {
        return department;
    }

    public void setDepartment(String department) {
        this.department = department;
    }

    public String getProjectCode() {
        return projectCode;
    }

    public void setProjectCode(String projectCode) {
        this.projectCode = projectCode;
    }

    public boolean isSignsInWithMicrosoft() {
        return signsInWithMicrosoft;
    }

    public void setSignsInWithMicrosoft(boolean signsInWithMicrosoft) {
        this.signsInWithMicrosoft = signsInWithMicrosoft;
    }

    public String getSignInMethod() {
        return signInMethod;
    }

    public void setSignInMethod(String signInMethod) {
        this.signInMethod = signInMethod;
    }

    /** How a person signs in, from the account itself (not from the current session). */
    static String signInMethodOf(User user) {
        if (user.isServiceAccount()) return "NONE";
        boolean microsoft = user.getAzureAdObjectId() != null;
        boolean password = user.getPassword() != null && !user.getPassword().isBlank();
        if (microsoft && password) return "MICROSOFT_AND_PASSWORD";
        if (password) return "PASSWORD";
        return "MICROSOFT"; // linked to Entra, or onboarded without a password and expected to use Microsoft
    }

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
        boolean onboarded = user.getOnboardedBy() != null;
        dto.setPendingFirstLogin(onboarded && user.getLastLoginAt() == null);
        dto.setUnverified(onboarded && user.getAzureAdObjectId() == null);
        dto.setUsername(user.getUsername());
        dto.setHasPassword(user.getPassword() != null && !user.getPassword().isBlank());
        dto.setServiceAccount(user.isServiceAccount());
        String method = signInMethodOf(user);
        dto.setSignInMethod(method);
        dto.setSignsInWithMicrosoft(method.startsWith("MICROSOFT"));
        dto.setDepartment(user.getDepartment());
        dto.setProjectCode(user.getProjectCode());
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

    public boolean isPendingFirstLogin() {
        return pendingFirstLogin;
    }

    public void setPendingFirstLogin(boolean pendingFirstLogin) {
        this.pendingFirstLogin = pendingFirstLogin;
    }

    public boolean isUnverified() {
        return unverified;
    }

    public void setUnverified(boolean unverified) {
        this.unverified = unverified;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isHasPassword() {
        return hasPassword;
    }

    public void setHasPassword(boolean hasPassword) {
        this.hasPassword = hasPassword;
    }
}
