package com.tcgdigital.vmcontrol.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * An environment template as the catalogue shows it (E32-T01). {@code engineRef} is only filled
 * for administrators.
 */
public record EnvironmentTemplateDTO(
        String templateId,
        String name,
        String description,
        String engine,
        JsonNode engineRef,
        JsonNode parameterSchema,
        String awsAccountRef,
        String region,
        Integer defaultTtlHours,
        Integer maxTtlHours,
        boolean requiresApproval,
        String approverRole,
        List<String> allowedRoles,
        BigDecimal estimatedMonthlyCost,
        boolean active,
        Timestamp createdAt,
        Timestamp updatedAt
) {
}
