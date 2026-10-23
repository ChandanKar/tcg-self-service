package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Add many people in one step (U13-T02, AddUsers.dc.html): each from the directory
 * (directoryObjectId) or typed (email, name), with an optional department, project code and team;
 * one role for the new ones; optional access on environments or groups; previewed first
 * (dryRun). People who already use the app keep their role and profile: only access and team
 * are added for them. U20-T07: a new person may get an initial password (12 to 72 characters):
 * they then sign in with their email and it, and choose their own before anything else.
 */
public record BulkOnboardRequestDTO(
        @NotEmpty(message = "Add at least one person") @Size(max = 100, message = "At most 100 people at once")
        List<@Valid Person> people,
        @NotNull(message = "Choose the role") Role role,
        @Size(max = 20, message = "At most 20 environments at once") List<@Valid Access> access,
        @Size(max = 500) String notes,
        Boolean emailPeople
) {
    /** The role new people get; existing people keep theirs. */
    public enum Role { USER, ENV_ADMIN, ADMIN }

    public record Person(
            @Size(max = 100) String directoryObjectId,
            @Email(message = "Not an email address") @Size(max = 255) String email,
            @Size(max = 255) String displayName,
            @Size(max = 100, message = "Department can be at most 100 characters.") String department,
            @Size(max = 50, message = "Project code can be at most 50 characters.")
            @Pattern(regexp = "^$|[A-Za-z0-9._-]+", message = "Project code can only use letters, digits, dot, dash and underscore.")
            String projectCode,
            @Size(max = 100) String teamId,
            @Size(max = 72, message = "The initial password can be at most 72 characters.") String initialPassword) {

        /** Without an initial password: the person signs in with Microsoft. */
        public Person(String directoryObjectId, String email, String displayName, String department, String projectCode,
                      String teamId) {
            this(directoryObjectId, email, displayName, department, projectCode, teamId, null);
        }

        /** Never in logs. */
        @Override
        public String toString() {
            return "Person[email=" + email + ", initialPassword=" + (initialPassword == null ? "none" : "set") + "]";
        }
    }

    /** One environment, whole (ENVIRONMENT) or some of its groups (GROUP with groupIds), at a level for some days. */
    public record Access(
            @NotBlank String environmentId,
            AccessScopeType scopeType,
            List<String> groupIds,
            @NotNull(message = "Choose the access") AccessLevel accessLevel,
            @Min(value = 1, message = "durationDays must be at least 1")
            @Max(value = AccessDurations.HARD_MAX_DAYS, message = AccessDurations.MAX_MESSAGE) Integer durationDays) {
    }
}
