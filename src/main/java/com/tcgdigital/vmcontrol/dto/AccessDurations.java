package com.tcgdigital.vmcontrol.dto;

/**
 * Hard bounds for an access duration in days, checked on every request body (M18). The tighter,
 * configurable limits (access.grant.max-duration-days, access.request.max-duration-days) are
 * applied by EnvironmentAccessService.
 */
public final class AccessDurations {

    /** 5 years: keeps expires_at far inside the MySQL TIMESTAMP range (ends 2038). */
    public static final int HARD_MAX_DAYS = 1825;

    public static final String MIN_MESSAGE = "durationDays must be at least 1";
    public static final String MAX_MESSAGE = "durationDays must be at most " + HARD_MAX_DAYS;

    private AccessDurations() {
    }
}
