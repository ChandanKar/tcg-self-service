package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.BudgetActionType;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/**
 * A budget with this month's spend (E20-T01): actual cost where AWS billing has it, else the
 * estimate, over complete days up to {@code asOf}; the month-end forecast at the recent run rate.
 */
public record BudgetDTO(
        String budgetId,
        BudgetScopeType scopeType,
        String scopeId,
        /** The environment's or team's display name. */
        String scopeName,
        BigDecimal monthlyAmount,
        List<Integer> thresholds,
        boolean forecastAlert,
        BudgetActionType actionType,
        boolean enabled,
        String awsBudgetName,
        String month,
        LocalDate asOf,
        BigDecimal spendToDate,
        BigDecimal percentUsed,
        BigDecimal forecastMonthEnd,
        BigDecimal forecastPercent,
        String createdByUserId,
        Timestamp createdAt,
        Timestamp updatedAt) {
}
