package com.tcgdigital.vmcontrol.dto;

/**
 * One Microsoft Entra ID directory user returned by {@code GET /api/v1/directory/search},
 * for the admin onboarding picker.
 *
 * @param directoryObjectId Entra {@code id} (the {@code oid} claim) — passed back to
 *                          {@code POST /api/v1/users} so the server can re-fetch and trust
 *                          the authoritative record
 * @param email             {@code mail}, or {@code userPrincipalName} when {@code mail} is null
 * @param alreadyInApp      true when an {@code app_user} row already exists for this person
 * @param appUserId         that existing row's id when {@code alreadyInApp}, else null
 */
public record DirectoryUserDTO(
        String directoryObjectId,
        String displayName,
        String email,
        String userPrincipalName,
        boolean alreadyInApp,
        String appUserId
) {}
