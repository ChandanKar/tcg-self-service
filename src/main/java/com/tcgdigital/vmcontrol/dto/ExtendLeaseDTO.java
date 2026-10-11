package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Extend the running lease (E15-T04); a reason is needed when the owner must approve. */
public class ExtendLeaseDTO {

    @NotNull(message = "minutes is required")
    @Min(value = 15, message = "Extend by at least 15 minutes")
    @Max(value = 1440, message = "Extend by at most 1440 minutes at a time")
    private Integer minutes;

    @Size(max = 500, message = "The reason is at most 500 characters")
    private String reason;

    public Integer getMinutes() { return minutes; }
    public void setMinutes(Integer minutes) { this.minutes = minutes; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
