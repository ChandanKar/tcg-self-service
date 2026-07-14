package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * One row in the rightsizing table. Candidacy rule (applied by the service, not here): avg CPU
 * &lt; 5% AND peak CPU &lt; 30% over the trailing 30 days.
 */
public record RightsizingCandidateDTO(
        String vmId,
        String vmName,
        String environmentName,
        String currentInstanceType,
        String suggestedInstanceType,
        BigDecimal avgCpuUtilization,
        BigDecimal peakCpuUtilization,
        BigDecimal currentMonthlyCost,
        BigDecimal estimatedMonthlyCostAfter,
        BigDecimal estimatedMonthlySavings,
        Boolean costKnown
) {}
