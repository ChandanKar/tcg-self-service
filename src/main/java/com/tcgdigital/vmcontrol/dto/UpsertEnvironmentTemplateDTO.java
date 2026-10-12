package com.tcgdigital.vmcontrol.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Create or change an environment template (E32-T01). {@code engineRef} is a JSON object
 * (Service Catalog: productId, provisioningArtifactId, optional launchPathId; Terraform:
 * organization, workspacePrefix, optional module); {@code parameterSchema} a JSON array of
 * {key, label, type: string|number|enum, required, default, options, pattern}.
 */
public record UpsertEnvironmentTemplateDTO(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 4000) String description,
        @NotNull String engine,
        @NotNull JsonNode engineRef,
        JsonNode parameterSchema,
        @Size(max = 36) String awsAccountRef,
        @Size(max = 32) String region,
        Integer defaultTtlHours,
        Integer maxTtlHours,
        Boolean requiresApproval,
        String approverRole,
        List<String> allowedRoles,
        BigDecimal estimatedMonthlyCost
) {
}
