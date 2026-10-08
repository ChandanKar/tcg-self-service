package com.tcgdigital.vmcontrol.dto;

import java.sql.Timestamp;
import java.util.List;

/**
 * Response of {@code GET /api/v1/users/me/profile}: the signed-in user's own account, as shown
 * on the Overview tab of the My Account panel.
 *
 * @param authMethod               {@code ENTRA_ID} or {@code LOCAL}
 * @param previousLoginAt          the sign-in before the current one; null until the user has
 *                                 signed in twice since it started being recorded
 * @param onboardedByName          display name of the admin who onboarded this user; null for
 *                                 self-registered or legacy users
 * @param administeredEnvironments environments where the user holds ADMIN-level access
 * @param extensionWindowDays      a grant expiring within this many days can be extended
 * @param requestMaxDurationDays   the longest duration a request or extension may ask for
 */
public record MyProfileDTO(
        String userId,
        String email,
        String displayName,
        String companyName,
        boolean admin,
        boolean envAdmin,
        String authMethod,
        Timestamp lastLoginAt,
        Timestamp previousLoginAt,
        Timestamp createdAt,
        Timestamp onboardedAt,
        String onboardedByName,
        List<EnvironmentRef> administeredEnvironments,
        int extensionWindowDays,
        int requestMaxDurationDays
) {
    public record EnvironmentRef(String environmentId, String name) {}
}
