package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for applying a rightsizing recommendation — changes {@code vmId}'s instance type
 * to {@code targetInstanceType}. Only permitted while the VM is stopped.
 */
public record ApplyRightsizingRequestDTO(
        @NotBlank String vmId,
        @NotBlank String targetInstanceType
) {}
