package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * KPI-strip figures for the Cost Management overview. Each tile adds up the same numbers its
 * table shows (E08-T04): {@code idleWasteMonthlyCost} is the sum of the Idle table's Monthly Idle
 * Cost; {@code rightsizingPotentialSavings} and {@code rightsizingCandidateCount} cover only
 * scale-down candidates that save money; {@code scaleUpCandidateCount} counts the rest.
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
        Timestamp generatedAt,
        Integer scaleUpCandidateCount
) {}
