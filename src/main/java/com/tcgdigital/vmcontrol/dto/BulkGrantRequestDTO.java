package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Grant one level to many people on many environments or groups at once (U10-T02,
 * GrantAccess.dc.html). People are app users by email and/or teams (their members as of now).
 * Each target is a whole environment or some of its groups. durationDays counts from now;
 * clearExpiry grants with no end; neither keeps an existing end (a new grant then has none).
 * emailPeople also emails each person (the in-app message always goes).
 */
public record BulkGrantRequestDTO(
        @Size(max = 50, message = "At most 50 people at once") List<@NotBlank String> userEmails,
        @Size(max = 20, message = "At most 20 teams at once") List<@NotBlank String> teamIds,
        @NotEmpty(message = "Choose at least one environment") @Size(max = 20, message = "At most 20 environments at once")
        List<@Valid Target> targets,
        @NotNull(message = "Choose the access") AccessLevel accessLevel,
        @Min(value = 1, message = "durationDays must be at least 1")
        @Max(value = AccessDurations.HARD_MAX_DAYS, message = AccessDurations.MAX_MESSAGE) Integer durationDays,
        Boolean clearExpiry,
        @Size(max = 500) String notes,
        Boolean emailPeople
) {
    /** One environment, whole (ENVIRONMENT) or some of its groups (GROUP with groupIds). */
    public record Target(@NotBlank String environmentId, AccessScopeType scopeType, List<String> groupIds) {
    }
}
