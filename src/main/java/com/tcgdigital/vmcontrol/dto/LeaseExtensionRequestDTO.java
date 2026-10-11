package com.tcgdigital.vmcontrol.dto;

import java.sql.Timestamp;

/** A lease extension request as the UI shows it (E15-T04). */
public record LeaseExtensionRequestDTO(
        String requestId,
        String leaseId,
        String environmentId,
        String requesterUserId,
        String requesterName,
        int minutes,
        String reason,
        String status,
        Timestamp createdAt,
        String reviewedByUserId,
        Timestamp reviewedAt,
        String reviewNotes
) {
}
