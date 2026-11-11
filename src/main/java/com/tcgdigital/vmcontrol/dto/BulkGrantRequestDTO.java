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
 * directoryObjectIds are people from the Entra directory who are not in the app yet (U21-T01,
 * administrators only): the preview shows them as new, and applying adds them to the app in the
 * same transaction as their grants, so a refused or abandoned grant adds nobody.
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
        Boolean emailPeople,
        @Size(max = 50, message = "At most 50 people at once") List<@NotBlank String> directoryObjectIds
) {
    /** A grant for app users and teams only (no directory people). */
    public BulkGrantRequestDTO(List<String> userEmails, List<String> teamIds, List<Target> targets, AccessLevel accessLevel,
                               Integer durationDays, Boolean clearExpiry, String notes, Boolean emailPeople) {
        this(userEmails, teamIds, targets, accessLevel, durationDays, clearExpiry, notes, emailPeople, null);
    }

    /** The same grant with the directory people replaced by these app users' emails (once they are in the app). */
    public BulkGrantRequestDTO withDirectoryPeopleAs(List<String> emails) {
        List<String> all = new java.util.ArrayList<>(userEmails == null ? List.of() : userEmails);
        all.addAll(emails);
        return new BulkGrantRequestDTO(all, teamIds, targets, accessLevel, durationDays, clearExpiry, notes, emailPeople, null);
    }

    /** One environment, whole (ENVIRONMENT) or some of its groups (GROUP with groupIds). */
    public record Target(@NotBlank String environmentId, AccessScopeType scopeType, List<String> groupIds) {
    }
}
