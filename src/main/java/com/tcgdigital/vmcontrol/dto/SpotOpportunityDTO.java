package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * An on-demand VM or EKS node group in a Spot-eligible environment and what Spot would save
 * (E31-T04). {@code placementScore} is AWS's 1-10 likelihood of getting the Spot capacity (null
 * when unknown); only EKS node groups are {@code eligible} for conversion here.
 */
public record SpotOpportunityDTO(
        String vmId,
        String name,
        String environmentId,
        String environmentName,
        String kind,
        String instanceType,
        Integer nodeCount,
        BigDecimal onDemandMonthly,
        BigDecimal spotMonthly,
        BigDecimal savings,
        Integer placementScore,
        boolean eligible,
        String reason
) {
}
