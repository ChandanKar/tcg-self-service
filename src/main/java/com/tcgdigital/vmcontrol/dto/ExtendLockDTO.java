package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Extend the current user's lock by some minutes (E07-T03). */
public class ExtendLockDTO {

    @NotNull(message = "Minutes are required")
    @Min(value = 15, message = "Extend by at least 15 minutes")
    @Max(value = 480, message = "Extend by at most 480 minutes")
    private Integer minutes;

    public Integer getMinutes() {
        return minutes;
    }

    public void setMinutes(Integer minutes) {
        this.minutes = minutes;
    }
}
