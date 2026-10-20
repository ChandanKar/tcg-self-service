package com.tcgdigital.vmcontrol.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

/**
 * An environment template as the catalogue shows it (E32-T01). {@code engineRef} is only filled
 * for administrators. {@code vmLayout}: VMs per group in start order, "1|2|1" (U09-T09), or null.
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
        Timestamp updatedAt,
        String vmLayout
) {
}
