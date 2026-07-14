package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * One row in the full VM cost detail table.
 */
public record VmCostDetailDTO(
        String vmId,
        String vmName,
        String environmentName,
        String groupName,
        String provider,
        String instanceType,
        String region,
        String status,
        BigDecimal runtimeHours,
        BigDecimal monthlyCost,
        Boolean costKnown,
        Long storageGib
) {}
