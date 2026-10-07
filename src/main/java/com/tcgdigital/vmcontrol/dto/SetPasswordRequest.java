package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.service.AuthenticationService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for an admin setting a user's password.
 */
public class SetPasswordRequest {

    @NotBlank(message = "Password is required")
    @Size(min = AuthenticationService.MIN_PASSWORD_LENGTH, max = AuthenticationService.MAX_PASSWORD_LENGTH,
            message = "Password must be between {min} and {max} characters")
    private String password;

    public SetPasswordRequest() {
    }

    public SetPasswordRequest(String password) {
        this.password = password;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "SetPasswordRequest{}";
    }
}
