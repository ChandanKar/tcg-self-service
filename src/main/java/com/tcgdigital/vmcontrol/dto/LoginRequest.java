package com.tcgdigital.vmcontrol.dto;

/**
 * Request DTO for username/password login endpoint.
 */
public class LoginRequest {
    private String username;
    private String password;
    /** POST /api/auth/change-password only: the password the person chooses (U20-T07). */
    private String newPassword;

    public LoginRequest() {
    }

    public LoginRequest(String username, String password) {
        this.username = username;
        this.password = password;
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

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    @Override
    public String toString() {
        return "LoginRequest{" +
                "username='" + username + '\'' +
                '}';
    }
}

