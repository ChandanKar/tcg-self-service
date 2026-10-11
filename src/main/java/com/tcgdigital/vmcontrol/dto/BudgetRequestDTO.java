package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.BudgetActionType;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** Create or update body of a budget (E20-T01). Scope cannot change on update. */
public record BudgetRequestDTO(
        @NotNull(message = "Scope type is required") BudgetScopeType scopeType,
        @NotBlank(message = "Scope is required") @Size(max = 100, message = "Scope must be at most 100 characters") String scopeId,
        @NotNull(message = "Monthly amount is required")
        @DecimalMin(value = "0", inclusive = false, message = "Monthly amount must be greater than 0")
        @DecimalMax(value = "10000000", message = "Monthly amount must be at most 10,000,000") BigDecimal monthlyAmount,
        /** 1..4 different percentages 1..200; null means 50, 75, 90, 100. */
        List<Integer> thresholds,
        Boolean forecastAlert,
        BudgetActionType actionType,
        Boolean enabled) {
}
