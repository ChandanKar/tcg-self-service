package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * KPI-strip figures for the Cost Management overview.
 */
public record CostSummaryDTO(
        BigDecimal totalMonthlyCost,
        BigDecimal previousPeriodCost,
        BigDecimal monthOverMonthChangePercent,
        BigDecimal idleWasteMonthlyCost,
        Integer idleVmCount,
        BigDecimal rightsizingPotentialSavings,
        Integer rightsizingCandidateCount,
        Integer totalVmCount,
        Integer costKnownVmCount,
        Timestamp generatedAt
) {}
