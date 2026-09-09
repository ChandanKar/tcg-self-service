package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;

/**
 * One environment's row in the Weekly Cost Report. {@code weekOverWeekChangePercent} stays null
 * when there's no prior-week estimated cost to compare against (e.g. a brand-new environment) —
 * never fabricated as zero. {@code uptimeHours} is the environment's total VM running-hours over
 * the reporting week (0 when nothing ran), on the same runtime basis as {@code estimatedCost}.
 */
public record WeeklyCostReportRowDTO(
        String environmentId,
        String environmentName,
        BigDecimal estimatedCost,
        BigDecimal actualCost,
        BigDecimal weekOverWeekChangePercent,
        BigDecimal uptimeHours
) {}
