package com.tcgdigital.vmcontrol.dto;

/**
 * U29: a signed-in person changing their own password (My account). Lengths are checked by
 * AuthenticationService so the answer names the rule; neither value is ever logged.
 */
public class ChangeOwnPasswordRequest {
    private String currentPassword;
    private String newPassword;

    public ChangeOwnPasswordRequest() {
    }

    public ChangeOwnPasswordRequest(String currentPassword, String newPassword) {
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    @Override
    public String toString() {
        return "ChangeOwnPasswordRequest{}";
    }
}
