package com.tcgdigital.vmcontrol.dto;

import java.util.List;

/** A new API token (E34-T01): a name, the scopes (values such as environments:read) and days until it expires. */
public record CreateApiTokenDTO(String name, List<String> scopes, Integer expiresInDays) {
}
