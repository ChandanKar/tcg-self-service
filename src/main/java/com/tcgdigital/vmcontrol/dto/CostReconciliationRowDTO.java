package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * Per-environment estimated-vs-actual cost comparison over a reporting window — {@code
 * actualCost} is null until {@code ActualCostIngestionScheduler} has ingested at least one day
 * of real billing data for that environment. {@code variancePercent} is null whenever actual
 * cost isn't known yet, never computed against a zero/missing denominator.
 *
 * <p>{@code variancePercent} compares like with like (E08-T03, H18): the estimate on the days
 * that have actuals ({@code estimatedCostOnActualDays}) against those actuals.
 * {@code estimatedCost} stays the whole-window total; {@code daysWithActuals} of
 * {@code daysInWindow} tells how much of the window the actuals cover.
 */
public record CostReconciliationRowDTO(
        String environmentId,
        String environmentName,
        BigDecimal estimatedCost,
        BigDecimal actualCost,
        BigDecimal variancePercent,
        BigDecimal estimatedCostOnActualDays,
        int daysWithActuals,
        int daysInWindow
) {}
