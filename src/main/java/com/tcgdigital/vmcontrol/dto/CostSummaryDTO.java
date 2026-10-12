package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * KPI-strip figures for the Cost Management overview. Each tile adds up the same numbers its
 * table shows (E08-T04): {@code idleWasteMonthlyCost} is the sum of the Idle table's Monthly Idle
 * Cost; {@code rightsizingPotentialSavings} and {@code rightsizingCandidateCount} cover only
 * scale-down candidates that save money; {@code scaleUpCandidateCount} counts the rest.
 * {@code approximatePriceVmCount} VMs are priced with another region's rate (E08-T07).
 * {@code eksControlPlaneCost} is the EKS control-plane fee, already part of {@code totalMonthlyCost} (E26-T03).
 * {@code spotSavings} is what Spot VMs priced at their Spot rate saved against on demand, over
 * {@code spotVmCount} Spot VMs and node groups (E31-T02).
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
        Integer scaleUpCandidateCount,
        Integer approximatePriceVmCount,
        BigDecimal eksControlPlaneCost,
        BigDecimal spotSavings,
        Integer spotVmCount,
        /** Spot interruptions in the last 30 days (E31-T08). */
        Long spotInterruptions30d
) {}
