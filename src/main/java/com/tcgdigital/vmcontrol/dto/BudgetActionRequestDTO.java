package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.BudgetActionRequest;
import com.tcgdigital.vmcontrol.model.BudgetActionType;

import java.math.BigDecimal;
import java.sql.Timestamp;

/** A budget action awaiting or after an owner's decision (E20-T03). */
public record BudgetActionRequestDTO(
        String requestId,
        String budgetId,
        String environmentId,
        String environmentName,
        String month,
        BudgetActionType actionType,
        BudgetActionRequest.Status status,
        BigDecimal monthlyAmount,
        Timestamp requestedAt,
        String decidedByUserId,
        String decidedByName,
        Timestamp decidedAt,
        String decisionReason,
        String executionId) {
}
