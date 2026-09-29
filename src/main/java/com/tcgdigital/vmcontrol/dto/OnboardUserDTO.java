package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body for {@code POST /api/v1/users} — an admin onboarding a person who has not signed in yet.
 *
 * <p>Two paths:
 * <ul>
 *   <li><b>directory</b>: {@link #directoryObjectId} set and directory lookup enabled — the
 *       server re-fetches the person from Microsoft Graph and trusts <em>that</em> id / email /
 *       name; {@link #email} and {@link #displayName} in the body are ignored.</li>
 *   <li><b>manual</b>: no {@link #directoryObjectId} (or lookup disabled) — {@link #email} is
 *       required, {@link #displayName} defaults to the email local-part; the row is created
 *       without an Entra {@code oid} (unverified) and adopted by email on first login.</li>
 * </ul>
 *
 * <p>{@link #initialGrant}, when present, is applied in the same transaction as the user
 * create — its failure rolls the whole onboard back.
 */
public class OnboardUserDTO {

    @Size(max = 100)
    private String directoryObjectId;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 255)
    private String displayName;

    private boolean admin = false;

    private boolean envAdmin = false;

    @Valid
    private InitialGrant initialGrant;

    public String getDirectoryObjectId() {
        return directoryObjectId;
    }

    public void setDirectoryObjectId(String directoryObjectId) {
        this.directoryObjectId = directoryObjectId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public boolean isAdmin() {
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
    }

    public boolean isEnvAdmin() {
        return envAdmin;
    }

    public void setEnvAdmin(boolean envAdmin) {
        this.envAdmin = envAdmin;
    }

    public InitialGrant getInitialGrant() {
        return initialGrant;
    }

    public void setInitialGrant(InitialGrant initialGrant) {
        this.initialGrant = initialGrant;
    }

    /** Optional first access grant applied to the freshly-onboarded user. */
    public static class InitialGrant {

        @NotBlank
        private String environmentId;

        @NotNull
        private AccessLevel accessLevel;

        @NotNull
        private AccessScopeType scopeType;

        /** Required + non-empty when {@link #scopeType} is {@code GROUP}; enforced by the grant service. */
        private List<String> groupIds;

        private Integer durationDays;

        /** Optional reason, carried onto the grant and its audit entry. */
        private String notes;

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

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }
}
