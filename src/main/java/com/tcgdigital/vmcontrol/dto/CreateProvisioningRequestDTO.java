package com.tcgdigital.vmcontrol.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A user's request for an environment built from a template (E32-T03). */
public record CreateProvisioningRequestDTO(
        @NotBlank String templateId,
        @NotBlank @Size(max = 63) String environmentName,
        JsonNode parameters,
        Integer ttlHours,
        @Size(max = 2000) String justification
) {
}
