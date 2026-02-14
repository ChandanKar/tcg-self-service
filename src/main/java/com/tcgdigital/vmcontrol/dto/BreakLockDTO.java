package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO for breaking a lock (admin operation).
 */
public class BreakLockDTO {

    @NotBlank(message = "Reason is required when breaking a lock")
    private String reason;

    public BreakLockDTO() {
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

