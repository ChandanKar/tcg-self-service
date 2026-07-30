package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * Per-environment estimated-vs-actual cost comparison over a reporting window — {@code
 * actualCost} is null until {@code ActualCostIngestionScheduler} has ingested at least one day
 * of real billing data for that environment. {@code variancePercent} is null whenever actual
 * cost isn't known yet, never computed against a zero/missing denominator.
 */
public record CostReconciliationRowDTO(
        String environmentId,
        String environmentName,
        BigDecimal estimatedCost,
        BigDecimal actualCost,
        BigDecimal variancePercent
) {}
