package com.tcgdigital.vmcontrol.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A schedule suggested from an environment's usage (E19-T05).
 *
 * <p>{@code activityByHourOfWeek} has 168 values (index 0 = Monday 00:00 in {@code timezone}): the
 * share of the weeks in which any VM was active in that hour. Hours are VM-hours per week;
 * {@code alwaysOnDays} are days used around the clock, which get no window.
 */
public record ScheduleSuggestionDTO(
        String environmentId,
        String timezone,
        int weeks,
        List<AutomationRuleWindowDTO> windows,
        List<Double> activityByHourOfWeek,
        String confidence,
        int metricsCoveragePercent,
        BigDecimal currentRunningHoursPerWeek,
        BigDecimal suggestedHoursPerWeek,
        BigDecimal estimatedWeeklySaving,
        int unpricedVmCount,
        List<String> alwaysOnDays) {
}
