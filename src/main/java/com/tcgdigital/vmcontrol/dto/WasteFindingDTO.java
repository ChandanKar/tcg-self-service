package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.WasteFindingStatus;
import com.tcgdigital.vmcontrol.model.WasteFindingType;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** A waste finding for the Waste page (E22-T03); {@code details} is the JSON of type-specific facts. */
public record WasteFindingDTO(
        String findingId,
        WasteFindingType findingType,
        String region,
        String resourceId,
        String resourceName,
        String environmentId,
        String environmentName,
        String vmId,
        String details,
        BigDecimal estMonthlyCost,
        WasteFindingStatus status,
        Timestamp firstSeenAt,
        Timestamp lastSeenAt,
        Timestamp eligibleSince,
        String dismissedBy,
        String dismissedReason) {
}
