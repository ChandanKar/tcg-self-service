package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for applying a rightsizing recommendation — changes {@code vmId}'s instance type
 * to {@code targetInstanceType}. Only permitted while the VM is stopped.
 */
public record ApplyRightsizingRequestDTO(
        @NotBlank String vmId,
        @NotBlank
        @jakarta.validation.constraints.Pattern(regexp = "^[a-z0-9-]+\\.[a-z0-9]+$",
                message = "targetInstanceType must look like an instance type, e.g. t3.large")
        String targetInstanceType
) {}
