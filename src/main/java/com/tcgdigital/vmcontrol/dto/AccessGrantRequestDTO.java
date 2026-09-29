package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body for {@code POST /api/v1/access-grants} — an admin / env-admin granting access directly
 * to a user, at the environment level or to one or more groups within it.
 *
 * <p>The target person is identified by exactly one of:
 * <ul>
 *   <li>{@link #userEmail} — an existing {@code app_user} (someone who has signed in or been
 *       onboarded); the normal path.</li>
 *   <li>{@link #directoryObjectId} — an Entra ID directory object for a person who is <em>not</em>
 *       yet an {@code app_user}. The server onboards them as a normal user (no admin rights,
 *       pending first sign-in) and applies the grant in the same transaction. Requires the
 *       {@code ADMIN} role and {@code graph.directory.enabled=true}.</li>
 * </ul>
 */
public class AccessGrantRequestDTO {

    /** Existing app user's email. Provide this OR {@link #directoryObjectId}, not both. */
    private String userEmail;

    /**
     * Entra ID directory object id of a person not yet in the app — the server onboards them
     * as a normal user, then grants. Provide this OR {@link #userEmail}, not both.
     */
    @Size(max = 100)
    private String directoryObjectId;

    @NotBlank(message = "Environment id is required")
    private String environmentId;

    @NotNull(message = "Access level is required")
    private AccessLevel accessLevel;

    @NotNull(message = "Scope type is required")
    private AccessScopeType scopeType;

    /** Required and non-empty when {@link #scopeType} is {@code GROUP}; ignored otherwise. */
    private List<String> groupIds;

    /** Optional: days the grant stays valid. Null = permanent. */
    private Integer durationDays;

    /** Optional: when re-granting over a time-boxed grant, true drops its expiry. */
    private Boolean clearExpiry;

    /** Optional reason, shown in the audit log. */
    private String notes;

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getDirectoryObjectId() {
        return directoryObjectId;
    }

    public void setDirectoryObjectId(String directoryObjectId) {
        this.directoryObjectId = directoryObjectId;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public AccessLevel getAccessLevel() {
        return accessLevel;
    }

    public void setAccessLevel(AccessLevel accessLevel) {
        this.accessLevel = accessLevel;
    }

    public AccessScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(AccessScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public List<String> getGroupIds() {
        return groupIds;
    }

    public void setGroupIds(List<String> groupIds) {
        this.groupIds = groupIds;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
    }

    public Boolean getClearExpiry() {
        return clearExpiry;
    }

    public void setClearExpiry(Boolean clearExpiry) {
        this.clearExpiry = clearExpiry;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
