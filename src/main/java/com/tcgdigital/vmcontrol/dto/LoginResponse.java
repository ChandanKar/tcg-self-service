package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.User;

/**
 * Response DTO for username/password login endpoint.
 */
public class LoginResponse {
    private boolean success;
    private String message;
    private UserDTO user;
    /** U20-T07: the password was right but is an initial one: no session until a new one is chosen. */
    private boolean passwordChangeRequired;

    public LoginResponse() {
    }

    public LoginResponse(boolean success, String message, User user) {
        this.success = success;
        this.message = message;
        this.user = user != null ? UserDTO.fromEntity(user) : null;
    }

    public LoginResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
        this.user = null;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /** The right initial password: the person is asked for a new one, and no session is made. */
    public static LoginResponse changeRequired() {
        LoginResponse r = new LoginResponse(false, "Choose your own password to finish signing in.");
        r.passwordChangeRequired = true;
        return r;
    }

    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    public void setPasswordChangeRequired(boolean passwordChangeRequired) {
        this.passwordChangeRequired = passwordChangeRequired;
    }

    public UserDTO getUser() {
        return user;
    }

    public void setUser(UserDTO user) {
        this.user = user;
    }

    @Override
    public String toString() {
        return "LoginResponse{" +
                "success=" + success +
                ", message='" + message + '\'' +
                ", user=" + user +
                '}';
    }
}


