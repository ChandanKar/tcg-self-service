package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * One environment's row in the Weekly Cost Report. {@code weekOverWeekChangePercent} stays null
 * when there's no prior-week estimated cost to compare against (e.g. a brand-new environment) —
 * never fabricated as zero.
 */
public record WeeklyCostReportRowDTO(
        String environmentId,
        String environmentName,
        BigDecimal estimatedCost,
        BigDecimal actualCost,
        BigDecimal weekOverWeekChangePercent
) {}
