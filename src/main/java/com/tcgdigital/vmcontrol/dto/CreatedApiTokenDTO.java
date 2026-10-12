package com.tcgdigital.vmcontrol.dto;

import java.sql.Timestamp;
import java.util.List;

/** A token just created (E34-T01): the only answer that carries the token itself. */
public record CreatedApiTokenDTO(
        String token,
        String id,
        String name,
        String prefix,
        List<String> scopes,
        Timestamp expiresAt,
        Timestamp createdAt
) {
    public static CreatedApiTokenDTO of(String token, ApiTokenDTO dto) {
        return new CreatedApiTokenDTO(token, dto.id(), dto.name(), dto.prefix(), dto.scopes(), dto.expiresAt(), dto.createdAt());
    }
}
