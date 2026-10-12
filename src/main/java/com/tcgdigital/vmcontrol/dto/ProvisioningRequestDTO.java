package com.tcgdigital.vmcontrol.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.Timestamp;

/** A provisioning request as its requester and approvers see it (E32-T03). */
public record ProvisioningRequestDTO(
        String requestId,
        String templateId,
        String templateName,
        String requestedByUserId,
        String requestedByName,
        String environmentName,
        JsonNode parameters,
        String justification,
        Integer ttlHours,
        String status,
        String reviewedByName,
        Timestamp reviewedAt,
        String reviewNotes,
        String environmentId,
        Timestamp destroyAt,
        String errorMessage,
        Timestamp createdAt,
        boolean canApprove,
        boolean canCancel
) {
}
