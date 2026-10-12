package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.ApiToken;

import java.sql.Timestamp;
import java.util.List;

/** An API token as listed (E34-T01): its prefix, never the secret or its hash. */
public record ApiTokenDTO(
        String id,
        String name,
        String prefix,
        List<String> scopes,
        Timestamp expiresAt,
        Timestamp lastUsedAt,
        Timestamp createdAt,
        Timestamp revokedAt
) {
    public static ApiTokenDTO from(ApiToken t) {
        return new ApiTokenDTO(t.getTokenId(), t.getName(), t.getTokenPrefix(), t.scopeList(), t.getExpiresAt(),
                t.getLastUsedAt(), t.getCreatedAt(), t.getRevokedAt());
    }
}
