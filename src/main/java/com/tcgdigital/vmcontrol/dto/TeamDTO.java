package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A team (E23-T01), with how many active environments it owns. */
public record TeamDTO(String teamId, String name, String displayName, String costCenter, String entraGroupId,
                      boolean active, long environmentCount, long memberCount) {

    /** Create/update body; {@code name} (the slug) defaults to one made from the display name. */
    public record Request(
            @Size(max = 100, message = "Name must be at most 100 characters") String name,
            @NotBlank(message = "Display name is required") @Size(max = 255) String displayName,
            @Size(max = 100, message = "Cost center must be at most 100 characters") String costCenter) {}
}
