package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.Min;

/**
 * DTO for acquiring a lock.
 */
public class AcquireLockDTO {

    private String reason;

    @Min(value = 1, message = "Expected duration must be at least 1 minute")
    private Integer expectedDurationMinutes;

    public AcquireLockDTO() {
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Integer getExpectedDurationMinutes() {
        return expectedDurationMinutes;
    }

    public void setExpectedDurationMinutes(Integer expectedDurationMinutes) {
        this.expectedDurationMinutes = expectedDurationMinutes;
    }
}

