package com.tcgdigital.vmcontrol.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * The month-end forecast shared by the owner cost view (E18-T01) and budgets (E20-T01): spend
 * so far plus the average of the recent daily values for each day left in the month.
 */
final class CostRunRate {

    private CostRunRate() {
    }

    /** Null when there are no recent daily values to average. */
    static BigDecimal forecast(BigDecimal toDate, List<BigDecimal> recentDailyValues, int daysRemaining) {
        if (recentDailyValues.isEmpty()) {
            return null;
        }
        BigDecimal average = recentDailyValues.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(recentDailyValues.size()), 4, RoundingMode.HALF_UP);
        return toDate.add(average.multiply(BigDecimal.valueOf(Math.max(0, daysRemaining))));
    }
}
